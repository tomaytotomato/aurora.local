package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.NetworkStorageDevice;
import com.tomaytotomato.aurora.domain.NetworkStorageDevice.Protocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures are written from the documented {@code avahi-browse -alrpt}
 * format and the way several vendors are known to advertise, deliberately
 * <em>not</em> by pointing the code at one NAS and encoding what came back.
 * That approach produces something that works on exactly one person's
 * network, and the first different device — a Pi running Samba, a Mac with
 * sharing on — breaks it.
 */
class AvahiStorageParserTests {

  @Test
  void findsAPlainSambaBox() {
    String out = """
        +;enp0s31f6;IPv4;spare-pi;_smb._tcp;local
        =;enp0s31f6;IPv4;spare-pi;_smb._tcp;local;spare-pi.local;192.168.0.42;445;
        """;

    List<NetworkStorageDevice> found = AvahiStorageParser.parse(out);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).name()).isEqualTo("spare-pi");
    assertThat(found.get(0).host()).isEqualTo("spare-pi.local");
    assertThat(found.get(0).address()).isEqualTo("192.168.0.42");
    assertThat(found.get(0).protocols()).containsExactly(new Protocol(Protocol.SMB, 445));
    // No model published: null, not a guess from the hostname.
    assertThat(found.get(0).model()).isNull();
  }

  @Test
  void collapsesTheThreeRecordsAnAppleAwareNasAdvertisesIntoOneDevice() {
    // A Synology-shaped advertisement: SMB, AFP and Time Machine, all the
    // same box. Three cards for one device would be a bug the owner has to
    // reason about.
    String out = """
        =;eth0;IPv4;DiskStation;_smb._tcp;local;diskstation.local;192.168.0.50;445;"model=DS220j"
        =;eth0;IPv4;DiskStation;_afpovertcp._tcp;local;diskstation.local;192.168.0.50;548;
        =;eth0;IPv4;DiskStation\\032Time\\032Machine;_adisk._tcp;local;diskstation.local;192.168.0.50;445;
        """;

    List<NetworkStorageDevice> found = AvahiStorageParser.parse(out);

    assertThat(found).hasSize(1);
    NetworkStorageDevice nas = found.get(0);
    // Named by the protocol we would actually use, so the Time Machine
    // record does not rename the whole device.
    assertThat(nas.name()).isEqualTo("DiskStation");
    assertThat(nas.model()).isEqualTo("DS220j");
    assertThat(nas.protocols().stream().map(Protocol::kind))
        .containsExactly(Protocol.SMB, Protocol.AFP, Protocol.TIME_MACHINE);
  }

  @Test
  void keepsDistinctDevicesApartEvenWhenTheyShareAName() {
    String out = """
        =;eth0;IPv4;NAS;_smb._tcp;local;one.local;192.168.0.50;445;
        =;eth0;IPv4;NAS;_smb._tcp;local;two.local;192.168.0.51;445;
        """;

    assertThat(AvahiStorageParser.parse(out)).hasSize(2);
  }

  @Test
  void readsNamesTheWayAPersonWroteThem() {
    String out = """
        =;eth0;IPv4;Living\\032Room\\032NAS;_smb._tcp;local;lr.local;192.168.0.60;445;
        """;

    assertThat(AvahiStorageParser.parse(out).get(0).name()).isEqualTo("Living Room NAS");
  }

  @Test
  void survivesASemicolonInADeviceName() {
    // Escaped separators must not shift every field after them.
    String out = """
        =;eth0;IPv4;Bruce\\;s\\032NAS;_smb._tcp;local;b.local;192.168.0.61;445;
        """;

    List<NetworkStorageDevice> found = AvahiStorageParser.parse(out);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).address()).isEqualTo("192.168.0.61");
    assertThat(found.get(0).name()).isEqualTo("Bruce;s NAS");
  }

  @Test
  void prefersSmbOverNfsInTheOrderItOffersThem() {
    // SMB first because every platform in a house mounts it without
    // setup; NFS is faster but awkward on Windows and phones.
    String out = """
        =;eth0;IPv4;truenas;_nfs._tcp;local;truenas.local;192.168.0.70;2049;
        =;eth0;IPv4;truenas;_smb._tcp;local;truenas.local;192.168.0.70;445;
        """;

    assertThat(AvahiStorageParser.parse(out).get(0).protocols().stream().map(Protocol::kind))
        .containsExactly(Protocol.SMB, Protocol.NFS);
  }

  @Test
  void ignoresUnresolvedAnnouncementsAndUnrelatedServices() {
    String out = """
        +;eth0;IPv4;Some\\032Printer;_ipp._tcp;local
        =;eth0;IPv4;Some\\032Printer;_ipp._tcp;local;printer.local;192.168.0.80;631;
        +;eth0;IPv4;pending-nas;_smb._tcp;local
        """;

    // The printer is not storage; the '+' line is an announcement that was
    // never resolved, so there is no address to offer.
    assertThat(AvahiStorageParser.parse(out)).isEmpty();
  }

  @Test
  void skipsLinkLocalIpv6WhichCannotBeMountedWithoutAScope() {
    String out = """
        =;eth0;IPv6;nas;_smb._tcp;local;nas.local;fe80::1234:5678:9abc:def0;445;
        =;eth0;IPv4;nas;_smb._tcp;local;nas.local;192.168.0.90;445;
        """;

    List<NetworkStorageDevice> found = AvahiStorageParser.parse(out);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).address()).isEqualTo("192.168.0.90");
  }

  @Test
  void understandsAvahisFriendlyServiceLabelsToo() {
    // Verbatim from a real UGREEN NAS on a real LAN, browsed without -k:
    // avahi rewrites _smb._tcp through its service-type database, and the
    // first version of this code matched only the canonical type — so it
    // reported "no storage found" while the NAS sat there advertising.
    // The service passes -k now; this is the belt to that pair of braces,
    // because whether the database is installed varies by image.
    String out = """
        =;enp0s31f6;IPv4;CORONA;Microsoft Windows Network;local;corona.local;192.168.0.216;445;
        =;enp0s31f6;IPv4;CORONA;Device Info;local;corona.local;192.168.0.216;0;"model=MacSamba"
        """;

    List<NetworkStorageDevice> found = AvahiStorageParser.parse(out);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).name()).isEqualTo("CORONA");
    assertThat(found.get(0).protocols()).containsExactly(new Protocol(Protocol.SMB, 445));
    // "Device Info" is not storage, so its model=MacSamba — a Samba
    // emulation string, not a model anyone would recognise — is not
    // borrowed to badge the device.
    assertThat(found.get(0).model()).isNull();
  }

  @Test
  void anEmptyNetworkIsAnEmptyList_notAnError() {
    assertThat(AvahiStorageParser.parse("")).isEmpty();
    assertThat(AvahiStorageParser.parse(null)).isEmpty();
    assertThat(AvahiStorageParser.parse("+;eth0;IPv4;x;_smb._tcp;local\n")).isEmpty();
  }

  @Test
  void toleratesTruncatedOrOddLinesRatherThanThrowing() {
    // avahi output is a stream from a daemon; a partial final line on
    // timeout is normal and must not lose the records above it.
    String out = """
        =;eth0;IPv4;good;_smb._tcp;local;good.local;192.168.0.99;445;
        =;eth0;IPv4;truncated;_smb._tcp
        """;

    assertThat(AvahiStorageParser.parse(out)).hasSize(1);
  }
}
