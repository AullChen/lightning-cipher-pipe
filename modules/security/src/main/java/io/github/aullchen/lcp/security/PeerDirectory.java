package io.github.aullchen.lcp.security;

import io.github.aullchen.lcp.api.Bytes32;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit configured node/key/route allowlist. No key can be learned from a request. */
public final class PeerDirectory {
    public record Entry(String nodeId, String keyId, Set<String> routes, byte[] publicSpki) {
        public Entry {
            identifier(nodeId); identifier(keyId); routes = Set.copyOf(routes);
            if (routes.isEmpty()) throw new IllegalArgumentException("Missing routes");
            routes.forEach(PeerDirectory::identifier); publicSpki = publicSpki.clone(); HpkeKey.publicKey(publicSpki);
        }
        @Override public byte[] publicSpki() { return publicSpki.clone(); }
    }
    private record Key(String node, String key) {}
    private final Map<Key, Entry> entries;
    private final Set<Key> revoked = ConcurrentHashMap.newKeySet();
    public PeerDirectory(Collection<Entry> entries) {
        var map = new HashMap<Key, Entry>();
        for (Entry entry : entries) if (map.putIfAbsent(new Key(entry.nodeId(), entry.keyId()), entry) != null)
            throw new IllegalArgumentException("Duplicate key identity");
        this.entries = Map.copyOf(map);
    }
    static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid identifier");
    }
    public void revoke(String node, String key) {
        Key identity = new Key(node, key);
        if (!entries.containsKey(identity)) throw new IllegalArgumentException("Unknown configured key");
        revoked.add(identity);
    }
    public void authorizeRoute(String node, String route) {
        for (var entry : entries.entrySet()) if (!revoked.contains(entry.getKey()) && entry.getValue().nodeId().equals(node) && entry.getValue().routes().contains(route)) return;
        throw new AuthenticationException();
    }
    X25519PublicKeyParameters require(String node, String route, String keyId, Bytes32 hash) {
        Key key = new Key(node, keyId); Entry entry = entries.get(key);
        if (entry == null || revoked.contains(key) || !entry.routes().contains(route)
                || !MetadataCodec.hash(entry.publicSpki()).equals(hash)) throw new AuthenticationException();
        return HpkeKey.publicKey(entry.publicSpki());
    }
}
