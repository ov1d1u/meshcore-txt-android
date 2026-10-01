package io.meshcore.teletext;

import com.darkrockstudios.libs.meshcore.DeviceConnection;
import com.darkrockstudios.libs.meshcore.protocol.CommandQueue;

/** Accesses MeshCoreKmp 0.12.2's JVM-visible queue for streamed contacts. */
final class MeshCoreCommandQueue {
    private MeshCoreCommandQueue() {}

    static CommandQueue forConnection(DeviceConnection connection) {
        return connection.getCommandQueue$MeshCore_release();
    }
}
