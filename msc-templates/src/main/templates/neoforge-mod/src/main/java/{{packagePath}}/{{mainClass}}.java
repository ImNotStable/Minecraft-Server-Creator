package {{packageName}};

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = {{mainClass}}.MOD_ID, dist = Dist.DEDICATED_SERVER)
public class {{mainClass}} {
    public static final String MOD_ID = "{{modId}}";

    public {{mainClass}}() {
    }
}
