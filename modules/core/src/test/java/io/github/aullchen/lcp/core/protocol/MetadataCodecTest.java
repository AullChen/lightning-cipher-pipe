package io.github.aullchen.lcp.core.protocol;

import io.github.aullchen.lcp.api.Bytes32;
import io.github.aullchen.lcp.api.Metadata.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class MetadataCodecTest {
    static final Properties GOLDEN = load();
    static final UUID TRANSFER = UUID.fromString("00112233-4455-4677-8899-aabbccddeeff");
    static final UUID COMMAND = UUID.fromString("11223344-5566-4788-99aa-bbccddeeff00");
    static final Limits LIMITS = new Limits(9437184, 8388608, 1000000, 1099511627776L, 16);
    static final Policy POLICY = new Policy(0, 1, 262144, 262144, 262144, 1, 1, 1, 1, 1, 1);
    static Properties load() {
        var properties = new Properties();
        try (var in = MetadataCodecTest.class.getResourceAsStream("/golden.properties")) { properties.load(Objects.requireNonNull(in)); }
        catch (IOException e) { throw new AssertionError(e); }
        return properties;
    }
    static byte[] resource(String name) {
        try (var in = MetadataCodecTest.class.getResourceAsStream("/" + name)) { return Objects.requireNonNull(in).readAllBytes(); }
        catch (IOException e) { throw new AssertionError(e); }
    }
    static byte[] vector(String key) { return HexFormat.of().parseHex(GOLDEN.getProperty(key)); }
    static Bytes32 filled(int n) { byte[] b = new byte[32]; Arrays.fill(b, (byte) n); return new Bytes32(b); }
    static OpenRequest request() {
        byte[] challenge = new byte[32]; for (int i = 0; i < 32; i++) challenge[i] = (byte) i;
        return new OpenRequest(TRANSFER, "demo", "source-a", "target-a", new Bytes32(challenge), "source-key", filled(17), "target-key", filled(34), List.of(0, 1), POLICY, LIMITS, Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000060000L));
    }
    static Accepted accepted() { return new Accepted(TRANSFER, filled(51), "handle-1", 0, POLICY, LIMITS, request().expiresAt()); }
    static Bytes32 binding() { return new Bytes32(vector("binding.sha256")); }

    @Test void independentOpenAndBinding() {
        assertArrayEquals(vector("open.cbor"), MetadataCodec.encode(request()));
        assertEquals(request(), MetadataCodec.decode(vector("open.cbor"), OpenRequest.class));
        assertArrayEquals(vector("accepted.cbor"), MetadataCodec.encode(accepted()));
        assertEquals(accepted(), MetadataCodec.decode(vector("accepted.cbor"), Accepted.class));
        assertArrayEquals(vector("open.sha256"), MetadataCodec.openRequestHash(request()).bytes());
        assertEquals(binding(), MetadataCodec.bindingHash(request(), accepted(), filled(68), filled(85)));
        var response = new OpenResponse(accepted(), MetadataCodec.openRequestHash(request()), binding());
        assertArrayEquals(vector("response.cbor"), MetadataCodec.encode(response));
        assertEquals(response, MetadataCodec.decode(vector("response.cbor"), OpenResponse.class));
        assertArrayEquals(vector("hpke-info.cbor"), MetadataCodec.hpkeInfo(binding(), 0));
        assertNotEquals(binding(), MetadataCodec.bindingHash(request(), accepted(), filled(69), filled(85)));
    }

    @ParameterizedTest @CsvSource({"empty,0,0", "one,1,1", "three,3,6"})
    void independentFinish(String name, long chunks, long bytes) {
        var manifest = new FinishManifest(TRANSFER, binding(), COMMAND, chunks, bytes, new Bytes32(vector(name + ".root")));
        assertArrayEquals(vector(name + "-finish.cbor"), MetadataCodec.encode(manifest));
        assertEquals(manifest, MetadataCodec.decode(vector(name + "-finish.cbor"), FinishManifest.class));
        assertArrayEquals(vector(name + "-finish.sha256"), MetadataCodec.manifestHash(manifest).bytes());
    }

    @Test void independentAadAndCancel() {
        var chunk = new ChunkAad(TRANSFER, binding(), 0, 0, 1, 1, 0);
        assertArrayEquals(vector("chunk-aad.cbor"), MetadataCodec.encode(chunk));
        assertEquals(chunk, MetadataCodec.decode(vector("chunk-aad.cbor"), ChunkAad.class));
        var finish = new FinishAad(TRANSFER, binding(), COMMAND);
        assertArrayEquals(vector("finish-aad.cbor"), MetadataCodec.encode(finish));
        assertEquals(finish, MetadataCodec.decode(vector("finish-aad.cbor"), FinishAad.class));
        var cancel = new CancelCommand(TRANSFER, COMMAND, binding());
        assertArrayEquals(vector("cancel.cbor"), MetadataCodec.encode(cancel));
        assertEquals(cancel, MetadataCodec.decode(vector("cancel.cbor"), CancelCommand.class));
    }

    @ParameterizedTest @CsvSource({"0,00", "23,17", "24,1818", "255,18ff", "256,190100", "65535,19ffff", "65536,1a00010000", "4294967295,1affffffff", "4294967296,1b0000000100000000", "9223372036854775807,1b7fffffffffffffff"})
    void shortestUnsignedBoundaries(long n, String hex) {
        byte[] expected = HexFormat.of().parseHex(hex);
        assertArrayEquals(expected, CanonicalCbor.encode(n));
        assertEquals(n, CanonicalCbor.decode(expected, 16));
    }

    @ParameterizedTest @ValueSource(strings = {"1800", "190018", "1a00000100", "1b0000000000010000", "1b8000000000000000", "20", "9fff", "f6", "f4", "a0", "c000", "f90000", "5a7fffffff", "9b7fffffffffffffff", "781061", "616180", "8181818181818181818100"})
    void rejectsNonCanonicalOrUnsupportedCbor(String hex) {
        assertThrows(ProtocolException.class, () -> CanonicalCbor.decode(HexFormat.of().parseHex(hex), 4096));
    }

    static Stream<Arguments> structures() {
        return Stream.of(Arguments.of("open", OpenRequest.class), Arguments.of("accepted", Accepted.class), Arguments.of("response", OpenResponse.class), Arguments.of("chunk-aad", ChunkAad.class), Arguments.of("finish-aad", FinishAad.class), Arguments.of("empty-finish", FinishManifest.class), Arguments.of("cancel", CancelCommand.class));
    }
    @ParameterizedTest @MethodSource("structures")
    void truncationAndTrailingItems(String name, Class<?> type) {
        byte[] good = vector(name + ".cbor");
        for (int i = 0; i < good.length; i++) {
            byte[] truncated = Arrays.copyOf(good, i);
            assertThrows(ProtocolException.class, () -> MetadataCodec.decode(truncated, type), "prefix " + i);
        }
        assertThrows(ProtocolException.class, () -> MetadataCodec.decode(Arrays.copyOf(good, good.length + 1), type));
    }

    @Test void rejectsWrongArrayDomainAndScalarTypes() {
        byte[] wrong = vector("open.cbor"); wrong[0] = (byte) 0x8e;
        assertThrows(ProtocolException.class, () -> MetadataCodec.decode(wrong, OpenRequest.class));
        byte[] domain = vector("open.cbor"); domain[2] = 'x';
        assertThrows(ProtocolException.class, () -> MetadataCodec.decode(domain, OpenRequest.class));
        var fields = new ArrayList<Object>(MetadataCodec.fields(request()));
        fields.set(1, new byte[15]);
        assertThrows(ProtocolException.class, () -> MetadataCodec.decode(CanonicalCbor.encode(fields), OpenRequest.class));
        var policy = new ArrayList<Object>(MetadataCodec.fields(POLICY)); policy.set(0, Long.MAX_VALUE);
        assertThrows(ProtocolException.class, () -> MetadataCodec.decode(CanonicalCbor.encode(policy), Policy.class));
    }

    @Test void jsonProjectionOrderWhitespaceAndWriter() {
        for (String name : List.of("open.json", "open-reordered.json")) {
            OpenRequest decoded = MetadataJson.decode(resource(name), OpenRequest.class);
            assertEquals(request(), decoded);
            assertArrayEquals(vector("open.cbor"), MetadataCodec.encode(decoded));
        }
        for (Object value : List.of(request(), accepted(), new OpenResponse(accepted(), MetadataCodec.openRequestHash(request()), binding()))) {
            assertEquals(value, MetadataJson.decode(MetadataJson.encode(value), value.getClass()));
        }
        String json = new String(MetadataJson.encode(request()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"maxTransferBytes\":\"1099511627776\""));
        assertTrue(json.contains("\"policyVersion\":1"));
    }

    static Stream<String> invalidJson() {
        String json = new String(resource("open.json"), StandardCharsets.UTF_8).trim();
        return Stream.of(
            json + " {}", json.substring(0, json.length() - 1),
            json.replace("\"routeId\":\"demo\"", "\"routeId\":\"demo\",\"routeId\":\"demo\""),
            json.replace("\"routeId\"", "\"unknown\""),
            json.replace("\"routeId\":\"demo\",", ""),
            json.replace("\"routeId\":\"demo\"", "\"routeId\":null"),
            json.replace("\"demo\"", "\"../demo\""),
            json.replace("\"1700000000000\"", "1700000000000"),
            json.replace("\"1700000000000\"", "\"01700000000000\""),
            json.replace("\"1700000000000\"", "\"9223372036854775808\""),
            json.replace("\"1700000000000\"", "\"-1\""),
            json.replace("\"mode\":0", "\"mode\":0.0"),
            json.replace("\"mode\":0", "\"mode\":\"0\""),
            json.replace("\"mode\":0", "\"mode\":-0"),
            json.replace("\"mode\":0", "\"mode\":4294967296"),
            json.replace("[0,1]", "[0,0]"), json.replace("[0,1]", "[]"), json.replace("[0,1]", "[0,1,0]"),
            json.replace("[0,1]", "[0,2]"),
            json.replace(TRANSFER.toString(), "00112233-4455-4677-8899-AABBCCDDEEFF"),
            json.replace("\"sourceChallenge\":\"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8\"", "\"sourceChallenge\":\"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=\""),
            json.replace("\"maxFrameBytes\":\"9437184\"", "\"maxFrameBytes\":\"16777217\""));
    }
    @ParameterizedTest @MethodSource("invalidJson") void strictJsonRejects(String json) {
        assertThrows(ProtocolException.class, () -> MetadataJson.decode(json.getBytes(StandardCharsets.UTF_8), OpenRequest.class));
    }

    @Test void boundedMetadataAndImmutableValues() {
        assertEquals(ProtocolException.Code.LIMIT_EXCEEDED, assertThrows(ProtocolException.class, () -> MetadataJson.decode(new byte[65537], OpenRequest.class)).code());
        assertThrows(ProtocolException.class, () -> MetadataCodec.decode(new byte[1025], FinishManifest.class));
        assertThrows(ProtocolException.class, () -> MetadataCodec.decode(new byte[4097], ChunkAad.class));
        byte[] original = new byte[32]; var value = new Bytes32(original); original[0] = 1; value.bytes()[1] = 2;
        assertEquals(filled(0), value);
        assertThrows(UnsupportedOperationException.class, () -> request().compressionOffers().add(1));
    }

    @Test void valueInvariantsRejectOverflowAndInvalidRanges() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkAad(TRANSFER, binding(), 0, Long.MAX_VALUE, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkAad(TRANSFER, binding(), 0, 0, 1, Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> new ChunkAad(TRANSFER, binding(), -1, 0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkAad(TRANSFER, binding(), 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkAad(TRANSFER, binding(), 0, 0, 1, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new Policy(0, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Policy(1, 1, 2, 3, 1, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FinishManifest(TRANSFER, binding(), COMMAND, 1, 0, filled(0)));
        assertThrows(IllegalArgumentException.class, () -> new Accepted(TRANSFER, filled(0), "ok", 0, POLICY, new Limits(1000, 1, 1, 1, 1), request().expiresAt()));
    }
}
