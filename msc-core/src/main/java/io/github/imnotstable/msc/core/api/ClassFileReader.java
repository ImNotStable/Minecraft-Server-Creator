package io.github.imnotstable.msc.core.api;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads just enough of a class file to list its public API: names, types, parameter names and
 * deprecation. No class loading, so missing dependencies do not matter.
 */
final class ClassFileReader {
    private static final int PUBLIC = 0x0001;
    private static final int PROTECTED = 0x0004;
    private static final int STATIC = 0x0008;
    private static final int FINAL = 0x0010;
    private static final int BRIDGE = 0x0040;
    private static final int INTERFACE = 0x0200;
    private static final int ABSTRACT = 0x0400;
    private static final int SYNTHETIC = 0x1000;
    private static final int ANNOTATION = 0x2000;
    private static final int ENUM = 0x4000;

    private Object[] pool;

    private ClassFileReader() {
    }

    /** Parse a class, or return null when it is not public API (not public, synthetic, anonymous). */
    static ClassInfo read(InputStream stream) throws IOException {
        Parsed p = readAny(stream);
        return p == null || !p.exported() ? null : p.info();
    }

    /** A parsed class; {@code exported} false for a package-private one, whose public members a public subtype shows. */
    record Parsed(ClassInfo info, boolean exported) {
    }

    /** Parse a class, public or not, or return null when it is synthetic, anonymous or module/package info. */
    static Parsed readAny(InputStream stream) throws IOException {
        return new ClassFileReader().parse(new DataInputStream(stream));
    }

    private String utf(int index) {
        return (String) pool[index];
    }

    private String className(int index) {
        return index == 0 ? null : utf((Integer) pool[index]);
    }

    private Parsed parse(DataInputStream in) throws IOException {
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readInt();
        int count = in.readUnsignedShort();
        pool = new Object[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3, 4 -> skip(in, 4);
                case 5, 6 -> {
                    skip(in, 8);
                    i++;
                }
                case 7 -> pool[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> skip(in, 2);
                case 9, 10, 11, 12, 17, 18 -> skip(in, 4);
                case 15 -> skip(in, 3);
                default -> throw new IOException("unknown constant tag " + tag);
            }
        }
        int access = in.readUnsignedShort();
        String thisName = className(in.readUnsignedShort());
        String superName = className(in.readUnsignedShort());
        List<String> supertypes = new ArrayList<>();
        if (superName != null && !superName.equals("java/lang/Object") && !superName.equals("java/lang/Enum")
                && !superName.equals("java/lang/Record")) {
            supertypes.add(dotted(superName));
        }
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) {
            String name = className(in.readUnsignedShort());
            if (!name.equals("java/lang/annotation/Annotation")) {
                supertypes.add(dotted(name));
            }
        }
        boolean isInterface = (access & INTERFACE) != 0;
        List<String> constants = new ArrayList<>();
        List<ClassInfo.Member> members = new ArrayList<>();
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            int flags = in.readUnsignedShort();
            String name = utf(in.readUnsignedShort());
            String descriptor = utf(in.readUnsignedShort());
            Attributes a = attributes(in);
            if ((flags & (PUBLIC | PROTECTED)) == 0 || (flags & SYNTHETIC) != 0) {
                continue;
            }
            if ((flags & ENUM) != 0) {
                constants.add(name);
                continue;
            }
            String type = TypeNames.field(a.signature != null ? a.signature : descriptor);
            String text = ((flags & STATIC) != 0 ? "static " : "") + ((flags & FINAL) != 0 ? "final " : "")
                    + type + " " + name;
            members.add(new ClassInfo.Member(name, text, a.deprecated, false));
        }
        int methods = in.readUnsignedShort();
        String simple = TypeNames.simple(thisName);
        String ownName = simple.substring(simple.lastIndexOf('.') + 1);
        for (int i = 0; i < methods; i++) {
            int flags = in.readUnsignedShort();
            String name = utf(in.readUnsignedShort());
            String descriptor = utf(in.readUnsignedShort());
            Attributes a = attributes(in);
            if ((flags & (PUBLIC | PROTECTED)) == 0 || (flags & (SYNTHETIC | BRIDGE)) != 0
                    || name.equals("<clinit>")) {
                continue;
            }
            boolean enumType = (access & ENUM) != 0;
            if (enumType && (name.equals("values") || name.equals("valueOf") || name.equals("<init>"))) {
                continue;
            }
            List<String> types;
            try {
                types = TypeNames.method(a.signature != null ? a.signature : descriptor);
            } catch (RuntimeException e) {
                types = TypeNames.method(descriptor);
            }
            String returns = types.get(types.size() - 1);
            int params = types.size() - 1;
            StringBuilder text = new StringBuilder();
            if ((flags & STATIC) != 0) {
                text.append("static ");
            } else if (isInterface && (flags & ABSTRACT) == 0) {
                text.append("default ");
            }
            boolean constructor = name.equals("<init>");
            if (constructor) {
                text.append(ownName);
            } else {
                text.append(returns).append(' ').append(name);
            }
            text.append('(');
            boolean named = a.parameterNames != null && a.parameterNames.size() == params;
            for (int p = 0; p < params; p++) {
                if (p > 0) {
                    text.append(", ");
                }
                text.append(types.get(p));
                if (named && a.parameterNames.get(p) != null) {
                    text.append(' ').append(a.parameterNames.get(p));
                }
            }
            text.append(')');
            members.add(new ClassInfo.Member(constructor ? ownName : name, text.toString(), a.deprecated, true));
        }
        Attributes classAttributes = attributes(in);
        if ((access & SYNTHETIC) != 0 || thisName.equals("module-info")
                || thisName.endsWith("package-info") || ownName.matches("\\d+.*")) {
            return null;
        }
        String kind = (access & ANNOTATION) != 0 ? "annotation" : isInterface ? "interface"
                : (access & ENUM) != 0 ? "enum" : "java/lang/Record".equals(superName) ? "record" : "class";
        return new Parsed(new ClassInfo(dotted(thisName), simple, kind, supertypes, classAttributes.deprecated, constants, members),
                (access & PUBLIC) != 0);
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }

    private static final class Attributes {
        String signature;
        boolean deprecated;
        List<String> parameterNames;
    }

    private Attributes attributes(DataInputStream in) throws IOException {
        Attributes out = new Attributes();
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            String name = utf(in.readUnsignedShort());
            int length = in.readInt();
            switch (name) {
                case "Signature" -> out.signature = utf(in.readUnsignedShort());
                case "Deprecated" -> {
                    out.deprecated = true;
                    skip(in, length);
                }
                case "MethodParameters" -> {
                    int n = in.readUnsignedByte();
                    out.parameterNames = new ArrayList<>();
                    for (int p = 0; p < n; p++) {
                        int nameIndex = in.readUnsignedShort();
                        int flags = in.readUnsignedShort();
                        // Skip synthetic and mandated parameters so names line up with the signature.
                        if ((flags & (0x1000 | 0x8000)) == 0) {
                            out.parameterNames.add(nameIndex == 0 ? null : utf(nameIndex));
                        }
                    }
                }
                default -> skip(in, length);
            }
        }
        return out;
    }

    private static void skip(DataInputStream in, int length) throws IOException {
        int left = length;
        while (left > 0) {
            int n = in.skipBytes(left);
            if (n <= 0) {
                in.readByte();
                n = 1;
            }
            left -= n;
        }
    }
}
