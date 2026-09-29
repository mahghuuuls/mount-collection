package com.mahghuuuls.mountcollection.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RelocationMessageTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }
    private RelocationMessage message() {
        EntityBoat boat=new EntityBoat(null); boat.setPosition(319.5,80,318.5);
        boat.motionX=.12; boat.onGround=true;
        return new RelocationMessage(UUID.randomUUID(),1,boat);
    }
    @Test void exactCodecPreservesIdentityAndNativePose() {
        RelocationMessage source=message(); ByteBuf bytes=Unpooled.buffer(); source.toBytes(bytes);
        assertEquals(RelocationMessage.BYTES,bytes.readableBytes());
        RelocationMessage decoded=new RelocationMessage(); decoded.fromBytes(bytes);
        assertEquals(source.session(),decoded.session()); assertEquals(source.entity(),decoded.entity());
        assertEquals(source.entityId(),decoded.entityId()); assertEquals(319.5,decoded.x());
        assertEquals(80,decoded.y()); assertEquals(318.5,decoded.z()); assertEquals(.12,decoded.vx());
        assertTrue(decoded.grounded()); assertEquals(0,bytes.readableBytes()); bytes.release();
    }
    @Test void strictLengthRevisionFlagSequenceAndNumbersRejectBeforeApplication() {
        for(int variant=0;variant<12;variant++) {
            ByteBuf bytes=Unpooled.buffer(); message().toBytes(bytes);
            switch(variant) {
                case 0: bytes.writeByte(0); break;
                case 1: bytes.writerIndex(bytes.writerIndex()-1); break;
                case 2: bytes.setInt(0,2); break;
                case 3: bytes.setByte(RelocationMessage.BYTES-1,2); break;
                case 4: bytes.setLong(20,0); break;
                case 5: bytes.setDouble(52,Double.NaN); break;
                case 6: bytes.setDouble(52,Double.POSITIVE_INFINITY); break;
                case 7: bytes.setLong(20,-1); break;
                case 8: bytes.setFloat(76,Float.NaN); break;
                case 9: bytes.setFloat(80,91); break;
                case 10: bytes.setDouble(84,Double.NEGATIVE_INFINITY); break;
                default: bytes.setDouble(52,30000001); break;
            }
            assertThrows(IllegalArgumentException.class,()->new RelocationMessage().fromBytes(bytes));
            bytes.release();
        }
    }
    @Test void renewedProtocolCarriesWorldAndRejectsOldLayout() {
        UUID session=UUID.randomUUID(); ByteBuf bytes=Unpooled.buffer();
        new ExperienceProtocol(session,-1).toBytes(bytes);
        ExperienceProtocol decoded=new ExperienceProtocol(); decoded.fromBytes(bytes);
        assertEquals(session,decoded.getSession()); assertEquals(-1,decoded.getDimension()); bytes.release();
        ByteBuf old=Unpooled.buffer().writeInt(2).writeLong(0).writeLong(0);
        assertThrows(IllegalArgumentException.class,()->new ExperienceProtocol().fromBytes(old)); old.release();
    }
    @Test void sequenceExhaustionIsDetectableBeforeAllocatingAnotherMessage() throws Exception {
        ContextualSession session=new ContextualSession();
        assertEquals(1,session.nextRelocation()); assertFalse(session.relocationExhausted());
        java.lang.reflect.Field field=ContextualSession.class.getDeclaredField("relocationSequence");
        field.setAccessible(true); field.setLong(session,Long.MAX_VALUE);
        assertTrue(session.relocationExhausted()); assertThrows(IllegalStateException.class,session::nextRelocation);
        ContextualSession renewed=new ContextualSession(); assertNotEquals(session.id(),renewed.id());
        assertEquals(1,renewed.nextRelocation());
    }
}
