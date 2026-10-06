package org.xiyu.reforged.shim.network;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraftforge.network.Channel;
import net.neoforged.neoforge.network.registration.HandlerThread;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.xiyu.reforged.shim.network.PayloadChannelRegistry.*;

class PayloadContractTest {
    @Test void phaseAndDirectionAreBothEnforced() {
        Contract play = new Contract(PayloadPhase.PLAY,PacketFlow.SERVERBOUND,"v1",false,HandlerThread.MAIN);
        assertTrue(play.permits(ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND));
        assertFalse(play.permits(ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND));
        assertFalse(play.permits(ConnectionProtocol.CONFIGURATION,PacketFlow.SERVERBOUND));
        Contract config = new Contract(PayloadPhase.CONFIGURATION,null,"v1",false,HandlerThread.NETWORK);
        assertTrue(config.permits(ConnectionProtocol.CONFIGURATION,PacketFlow.CLIENTBOUND));
        assertFalse(config.permits(ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND));
        Contract common = new Contract(PayloadPhase.COMMON,null,"v1",false,HandlerThread.MAIN);
        assertTrue(common.permits(ConnectionProtocol.CONFIGURATION,PacketFlow.SERVERBOUND));
        assertTrue(common.permits(ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND));
        assertFalse(common.permits(ConnectionProtocol.LOGIN,PacketFlow.SERVERBOUND));
    }
    @Test void requiredMissingAndWrongVersionAreRejected() {
        Contract required = new Contract(PayloadPhase.PLAY,null,"one",false,HandlerThread.MAIN);
        assertFalse(required.acceptsRemote(Channel.VersionTest.Status.MISSING,0));
        assertFalse(required.acceptsRemote(Channel.VersionTest.Status.VANILLA,0));
        assertTrue(required.acceptsRemote(Channel.VersionTest.Status.PRESENT,required.protocolVersion()));
        Contract other = new Contract(PayloadPhase.PLAY,null,"two",false,HandlerThread.MAIN);
        assertFalse(required.acceptsRemote(Channel.VersionTest.Status.PRESENT,other.protocolVersion()));
    }
    @Test void optionalOnlyRelaxesAbsence() {
        Contract optional = new Contract(PayloadPhase.PLAY,null,"one",true,HandlerThread.MAIN);
        assertTrue(optional.acceptsRemote(Channel.VersionTest.Status.MISSING,0));
        assertTrue(optional.acceptsRemote(Channel.VersionTest.Status.VANILLA,0));
        assertFalse(optional.acceptsRemote(Channel.VersionTest.Status.PRESENT,optional.protocolVersion() ^ 1));
    }
    @Test void registrarModifiersAreImmutableAndPreservePreviousOptions() {
        PayloadRegistrar initial = new PayloadRegistrar("one");
        PayloadRegistrar configured = initial.optional().executesOn(HandlerThread.NETWORK).versioned("two");
        assertFalse(initial.isOptional());
        assertEquals(HandlerThread.MAIN,initial.getHandlerThread());
        assertTrue(configured.isOptional());
        assertEquals("two",configured.getVersion());
        assertEquals(HandlerThread.NETWORK,configured.getHandlerThread());
        assertThrows(IllegalArgumentException.class, () -> initial.executesOn("unknown"));
        assertThrows(IllegalArgumentException.class, () -> new PayloadRegistrar(""));
    }
}
