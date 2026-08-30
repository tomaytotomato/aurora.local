package com.tomaytotomato.aurora.domain;

import java.util.List;

/**
 * Something on the LAN that offers file storage.
 *
 * <p>Deliberately not called a "NAS": the same record covers a Synology, a
 * spare Raspberry Pi running Samba, another Aurora box, or a Mac with
 * sharing switched on. What matters to the owner is "this thing on my
 * network can hold files", not what its manufacturer calls it.
 *
 * @param name        the advertised name, as the device calls itself
 *                    ("Living Room NAS"). Never invented.
 * @param host        mDNS hostname (e.g. {@code diskstation.local}), if it
 *                    advertised one.
 * @param address     the IP it answered on.
 * @param protocols   which sharing protocols it advertises, in the order
 *                    Aurora would prefer them.
 * @param model       the model string the device published about itself,
 *                    when it published one. Null rather than guessed — a
 *                    wrong badge is worse than none.
 * @param reachable   whether the advertised port answered a connection
 *                    just now. Advertising and answering are different
 *                    claims, and a device that is asleep still advertises.
 */
public record NetworkStorageDevice(
    String name,
    String host,
    String address,
    List<Protocol> protocols,
    String model,
    boolean reachable
) {

  /** A file-sharing protocol Aurora knows how to talk about. */
  public record Protocol(String kind, int port) {
    public static final String SMB = "smb";
    public static final String NFS = "nfs";
    public static final String AFP = "afp";
    /** Apple Time Machine target, advertised alongside SMB or AFP. */
    public static final String TIME_MACHINE = "time-machine";
  }
}
