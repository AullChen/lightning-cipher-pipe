package io.github.aullchen.lcp.core.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.HexFormat;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class MerkleFrontierTest {
    @ParameterizedTest @ValueSource(ints = {0, 1, 3, 5, 7, 8, 13, 65})
    void matchesIndependentRecursiveTree(int count) throws Exception {
        var expected = new Properties();
        try (var in = getClass().getResourceAsStream("/merkle.properties")) { expected.load(in); }
        var tree = new MerkleFrontier();
        long offset = 0;
        for (int index = 0; index < count; index++) {
            byte[] bytes = new byte[index % 7 + 1];
            for (int j = 0; j < bytes.length; j++) bytes[j] = (byte) (index + j);
            tree.append(index, offset, bytes.length, MetadataCodec.hash(bytes)); offset += bytes.length;
            assertEquals(index + 1, tree.count());
        }
        assertEquals(expected.getProperty("root." + count), HexFormat.of().formatHex(tree.root().bytes()));
        assertEquals(tree.root(), tree.root());
    }

    @Test void everyDescriptorFieldIsCommittedAndBadOrderIsAtomic() {
        var h = MetadataCodec.hash(new byte[]{0});
        var base = MerkleFrontier.leaf(0, 0, 1, h);
        assertNotEquals(base, MerkleFrontier.leaf(1, 0, 1, h));
        assertNotEquals(base, MerkleFrontier.leaf(0, 1, 1, h));
        assertNotEquals(base, MerkleFrontier.leaf(0, 0, 2, h));
        assertNotEquals(base, MerkleFrontier.leaf(0, 0, 1, MetadataCodec.hash(new byte[]{1})));
        var tree = new MerkleFrontier(); tree.append(0, 0, 1, h);
        assertThrows(IllegalArgumentException.class, () -> tree.append(2, 1, 1, h));
        assertThrows(IllegalArgumentException.class, () -> tree.append(1, Long.MAX_VALUE, 1, h));
        assertEquals(base, tree.root()); assertEquals(1, tree.count());
    }
}
