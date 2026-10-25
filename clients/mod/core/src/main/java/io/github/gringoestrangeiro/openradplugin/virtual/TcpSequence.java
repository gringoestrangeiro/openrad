package io.github.gringoestrangeiro.openradplugin.virtual;

/** TCP serial arithmetic; all admitted windows are less than 2^31 bytes. */
public final class TcpSequence {
    private TcpSequence() {}

    public static long wire(long sequence) {
        return sequence & 0xffff_ffffL;
    }

    public static long unwrap(long wireSequence, long reference) {
        return reference + (int) (wireSequence - wire(reference));
    }
}
