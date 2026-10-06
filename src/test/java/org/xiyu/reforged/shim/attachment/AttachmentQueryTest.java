package org.xiyu.reforged.shim.attachment;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentQueryTest {
    @Test void missingQueriesDoNotCallFactory() {
        AtomicInteger calls = new AtomicInteger();
        AttachmentType<Integer> type = AttachmentType.builder(() -> calls.incrementAndGet()).build();
        IAttachmentHolder fallback = new IAttachmentHolder() {};
        AttachmentHolder field = new AttachmentHolder();
        for (IAttachmentHolder holder : new IAttachmentHolder[]{fallback,field}) {
            assertNull(holder.getExistingDataOrNull(type));
            assertTrue(holder.getExistingData(type).isEmpty());
            assertFalse(holder.hasAttachments());
            holder.setData(type,42);
            assertEquals(42,holder.getExistingDataOrNull(type));
            assertEquals(42,holder.getExistingData(() -> type).orElseThrow());
            assertEquals(42,holder.removeData(type));
            assertNull(holder.getExistingDataOrNull(type));
        }
        assertEquals(0,calls.get());
    }
    static class EqualHolder implements IAttachmentHolder {
        public int hashCode() { return 1; }
        public boolean equals(Object other) { return other instanceof EqualHolder; }
    }
    @Test void equalObjectsHaveDistinctStorageAndCleanup() {
        EqualHolder first = new EqualHolder(), second = new EqualHolder();
        AttachmentType<Integer> type = AttachmentType.builder(() -> 0).build();
        first.setData(type,1); second.setData(type,2);
        assertEquals(1,first.getData(type)); assertEquals(2,second.getData(type));
        IAttachmentHolder.GlobalAttachmentStorage.cleanup(first);
        assertFalse(first.hasData(type)); assertEquals(2,second.getData(type));
    }
    @Test void identityHashCollisionDoesNotShareData() throws Exception {
        EqualHolder first = new EqualHolder(), second = new EqualHolder();
        Class<?> key = Class.forName(IAttachmentHolder.GlobalAttachmentStorage.class.getName() + "$IdentityWeakReference");
        var constructor = key.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object a = constructor.newInstance(first,null), b = constructor.newInstance(second,null);
        var hash = key.getDeclaredField("identityHash"); hash.setAccessible(true); hash.setInt(b,a.hashCode());
        assertEquals(a.hashCode(),b.hashCode()); assertNotEquals(a,b);
        var map = new java.util.HashMap<Object,String>(); map.put(a,"A"); map.put(b,"B");
        assertEquals(2,map.size()); assertEquals("A",map.get(a)); assertEquals("B",map.get(b));
    }
}
