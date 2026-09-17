package io.github.aullchen.lcp.core.protocol;

import io.github.aullchen.lcp.api.Metadata.OpenRequest;
import io.github.aullchen.lcp.api.TransferStorage.*;
import java.util.List;

/** Small versioned source intent. Contains no payload, offsets or per-chunk history. */
public final class SourceControlCodec {
    private SourceControlCodec() {}
    public record Record(String endpoint, OpenRequest request, AcceptedTransfer accepted, byte[] completedStatus) {
        public Record {
            java.util.Objects.requireNonNull(endpoint); java.util.Objects.requireNonNull(request);
            var uri=java.net.URI.create(endpoint);
            if (endpoint.length()>512 || !"https".equals(uri.getScheme()) || !uri.toASCIIString().equals(endpoint) || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null) throw new IllegalArgumentException("Invalid endpoint");
            if (accepted != null && !accepted.request().equals(request)) throw new IllegalArgumentException("Mismatched intent");
            completedStatus = completedStatus == null ? new byte[0] : completedStatus.clone();
            if (completedStatus.length != 0) {
                var status = TransferJson.status(completedStatus);
                if (!status.transferId().equals(request.transferId()) || status.state()!=State.COMPLETED) throw new IllegalArgumentException("Invalid result");
            }
        }
        @Override public byte[] completedStatus() { return completedStatus.clone(); }
    }
    public static byte[] encode(Record record) {
        byte[] encoded = CanonicalCbor.encode(List.of(1,record.endpoint(),MetadataCodec.encode(record.request()),
                record.accepted()==null ? new byte[0] : StorageCodec.open(record.accepted()),record.completedStatus()));
        if (encoded.length>65536) throw ProtocolException.limit(); return encoded;
    }
    public static Record decode(byte[] bytes) {
        try {
            var values = (List<?>)CanonicalCbor.decode(bytes,65536,65536,512);
            if (values.size()!=5 || !Long.valueOf(1).equals(values.get(0))) throw ProtocolException.invalid();
            byte[] accepted = (byte[])values.get(3);
            return new Record((String)values.get(1),MetadataCodec.decode((byte[])values.get(2),OpenRequest.class),
                    accepted.length==0 ? null : StorageCodec.open(accepted),(byte[])values.get(4));
        } catch (RuntimeException e) { throw ProtocolException.invalid(); }
    }
}
