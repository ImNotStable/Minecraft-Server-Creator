package io.github.imnotstable.msc.core.fixture;

import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * A stand-in for the standalone pack host ({@code <host> <port> <dir>}) that is not one: it takes the port and accepts
 * connections, as anything else that took the port while it changed hands would, but never serves a pack or says it
 * does, and exits after two seconds (#753).
 */
public final class SilentListenerMain {
    private SilentListenerMain() {
    }

    public static void main(String[] args) throws Exception {
        ServerSocket taken = new ServerSocket(Integer.parseInt(args[1]), 50, InetAddress.getByName(args[0]));
        Thread.sleep(2000);
        taken.close();
    }
}
