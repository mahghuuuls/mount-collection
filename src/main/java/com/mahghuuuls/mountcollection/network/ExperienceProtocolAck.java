package com.mahghuuuls.mountcollection.network;

/** Separate wire type: SimpleImpl assigns one discriminator to each message class. */
public final class ExperienceProtocolAck extends ExperienceProtocol {
    public ExperienceProtocolAck() { }
    public ExperienceProtocolAck(java.util.UUID session) { super(session); }
}
