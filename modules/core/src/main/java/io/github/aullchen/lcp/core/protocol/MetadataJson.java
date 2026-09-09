package io.github.aullchen.lcp.core.protocol;

import com.fasterxml.jackson.core.*;
import io.github.aullchen.lcp.api.Metadata.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Strict HTTP JSON projection of Open structures, independent of property order/whitespace. */
public final class MetadataJson {
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(128).maxNameLength(64).maxNumberLength(19).build()).build();

    private record Shape(String domain, List<String> names, String kinds) {}
    private MetadataJson() {}

    private static Shape shape(Class<?> type) {
        if (type == Limits.class) return new Shape(null, List.of("maxFrameBytes", "maxPlainBytes", "maxChunks", "maxTransferBytes", "maxInFlightChunks"), "uuuuu");
        if (type == Policy.class) return new Shape(null, List.of("mode", "policyVersion", "minChunkBytes", "maxChunkBytes", "initialChunkBytes", "minWindow", "maxWindow", "initialWindow", "minZstdLevel", "maxZstdLevel", "initialZstdLevel"), "nnuuuuuunnn");
        if (type == OpenRequest.class) return new Shape("lcp-stream-open/1", List.of("transferId", "routeId", "sourceNodeId", "targetNodeId", "sourceChallenge", "sourceKeyId", "sourcePublicKeyHash", "targetKeyId", "targetPublicKeyHash", "compressionOffers", "policy", "limits", "createdAt", "expiresAt"), "isssbsbsbopluu");
        if (type == Accepted.class) return new Shape("lcp-stream-accepted/1", List.of("transferId", "targetChallenge", "handleId", "compressionCode", "policy", "limits", "expiresAt"), "ibsnplu");
        if (type == OpenResponse.class) return new Shape(null, List.of("accepted", "openRequestHash", "bindingHash"), "abb");
        throw ProtocolException.invalid();
    }

    public static <T> T decode(byte[] json, Class<T> type) {
        if (json.length > MetadataCodec.CONTROL_LIMIT) throw ProtocolException.limit();
        try (var parser = FACTORY.createParser(json)) {
            parser.nextToken();
            Object fields = readObject(parser, shape(type));
            if (parser.nextToken() != null) throw ProtocolException.invalid();
            return type.cast(MetadataCodec.object(fields, type));
        } catch (ProtocolException e) { throw e; }
        catch (IOException | IllegalArgumentException | ClassCastException | ArithmeticException | NullPointerException e) { throw ProtocolException.invalid(); }
    }

    private static List<?> readObject(JsonParser p, Shape s) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) throw ProtocolException.invalid();
        Object[] values = new Object[s.names.size()];
        while (p.nextToken() != JsonToken.END_OBJECT) {
            if (p.currentToken() != JsonToken.FIELD_NAME) throw ProtocolException.invalid();
            int index = s.names.indexOf(p.currentName());
            if (index < 0 || values[index] != null) throw ProtocolException.invalid();
            p.nextToken(); values[index] = readValue(p, s.kinds.charAt(index));
        }
        if (Arrays.stream(values).anyMatch(v -> v == null)) throw ProtocolException.invalid();
        var fields = new ArrayList<Object>();
        if (s.domain != null) fields.add(s.domain);
        fields.addAll(Arrays.asList(values)); return fields;
    }

    private static Object readValue(JsonParser p, char kind) throws IOException {
        if (kind == 'p' || kind == 'l' || kind == 'a') return readObject(p, shape(nested(kind)));
        if (kind == 'o') {
            if (p.currentToken() != JsonToken.START_ARRAY) throw ProtocolException.invalid();
            var offers = new ArrayList<Object>(2);
            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (offers.size() == 2) throw ProtocolException.invalid();
                offers.add(readValue(p, 'n'));
            }
            return offers;
        }
        if (p.currentToken() != (kind == 'n' ? JsonToken.VALUE_NUMBER_INT : JsonToken.VALUE_STRING)) throw ProtocolException.invalid();
        String text = p.getText();
        if (kind == 'u' || kind == 'n') {
            if (!text.matches("0|[1-9][0-9]{0,18}")) throw ProtocolException.invalid();
            return Long.parseLong(text);
        }
        if (kind == 'i') {
            UUID id = UUID.fromString(text);
            if (!id.toString().equals(text)) throw ProtocolException.invalid();
            return MetadataCodec.uuid(id);
        }
        if (kind == 'b') {
            byte[] bytes = Base64.getUrlDecoder().decode(text);
            if (bytes.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(text)) throw ProtocolException.invalid();
            return bytes;
        }
        return text;
    }

    private static Class<?> nested(char kind) { return kind == 'p' ? Policy.class : kind == 'l' ? Limits.class : Accepted.class; }

    public static byte[] encode(Object value) {
        var out = new ByteArrayOutputStream();
        try (var generator = FACTORY.createGenerator(out)) { writeObject(generator, shape(value.getClass()), MetadataCodec.fields(value)); }
        catch (IOException e) { throw new IllegalStateException(e); }
        return out.toByteArray();
    }

    private static void writeObject(JsonGenerator g, Shape s, List<?> fields) throws IOException {
        g.writeStartObject();
        for (int i = 0; i < s.names.size(); i++) {
            g.writeFieldName(s.names.get(i));
            Object v = fields.get(i + (s.domain == null ? 0 : 1));
            char kind = s.kinds.charAt(i);
            switch (kind) {
                case 'p', 'l', 'a' -> writeObject(g, shape(nested(kind)), (List<?>) v);
                case 'u' -> g.writeString(v.toString());
                case 'n' -> g.writeNumber(((Number) v).longValue());
                case 'i' -> g.writeString(MetadataCodec.uuid(v).toString());
                case 'b' -> g.writeString(Base64.getUrlEncoder().withoutPadding().encodeToString((byte[]) v));
                case 'o' -> { g.writeStartArray(); for (Object n : (List<?>) v) g.writeNumber(((Number) n).intValue()); g.writeEndArray(); }
                default -> g.writeString((String) v);
            }
        }
        g.writeEndObject();
    }
}
