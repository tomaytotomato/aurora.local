package com.tomaytotomato.aurora.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What folders a network device is offering, and whether the credentials
 * given actually open them.
 *
 * <p>The alternative — a text box asking the owner to type a share name —
 * requires them to go and look it up on the NAS's own admin UI, which is
 * the moment the "one screen for everything" promise breaks. Listing costs
 * one command.
 *
 * <p>Credentials are never logged, never persisted by this class, and are
 * passed to {@code smbclient} through the environment rather than argv, so
 * they do not appear in {@code ps} output on the host.
 */
@Service
public class SmbShareService {

  private static final Logger log = LoggerFactory.getLogger(SmbShareService.class);

  /**
   * Shares whose names mean "not for you": the Windows administrative
   * shares, the printer pseudo-share, and the IPC endpoint that every SMB
   * server advertises as a control channel. Offering these as places to
   * keep films would be actively unhelpful.
   */
  private static final List<String> HIDDEN = List.of("IPC$", "print$", "ADMIN$", "C$", "D$");

  /** {@code   Sharename       Type      Comment} table rows from smbclient -L. */
  private static final Pattern ROW =
      Pattern.compile("^\\s+(\\S+)\\s+(Disk|IPC|Printer)\\s*(.*)$");

  private final CommandRunner commands;

  public SmbShareService(CommandRunner commands) {
    this.commands = commands;
  }

  /** One folder offered by a device. */
  public record Share(String name, String comment) {}

  /** What happened when we asked. Distinguishes the three cases a person cares about. */
  public record Result(String outcome, List<Share> shares, String detail) {
    /** Listed successfully. */
    public static final String OK = "ok";
    /** The device answered, but not with these credentials. */
    public static final String DENIED = "denied";
    /** The device did not answer at all. */
    public static final String UNREACHABLE = "unreachable";
  }

  /**
   * List the shares on {@code address}.
   *
   * @param username null or blank for a guest listing, which is how most
   *                 NAS boxes are configured out of the box and saves
   *                 asking for credentials that may not be needed
   */
  public Result list(String address, String username, String password) {
    List<String> argv = new ArrayList<>(List.of("smbclient", "-L", "//" + address, "-g"));
    Map<String, String> env;
    if (username == null || username.isBlank()) {
      argv.add("-N");                       // no password prompt: guest
      env = Map.of();
    } else {
      argv.add("-U");
      argv.add(username);
      // Through the environment, not argv: anything in argv is visible in
      // `ps` to every user on the host.
      env = Map.of("PASSWD", password == null ? "" : password);
    }

    var out = new StringBuilder();
    int exit;
    try {
      exit = commands.stream(null, env, argv, line -> out.append(line).append('\n'));
    } catch (Exception e) {
      log.debug("smbclient against {} failed: {}", address, e.toString());
      return new Result(Result.UNREACHABLE, List.of(),
          "Aurora couldn't reach that device just now.");
    }

    String text = out.toString();
    if (exit != 0) {
      // smbclient says NT_STATUS_LOGON_FAILURE for bad credentials and
      // NT_STATUS_ACCESS_DENIED for a share-level refusal; both mean "your
      // details did not work", which is what the owner needs to know.
      if (text.contains("LOGON_FAILURE") || text.contains("ACCESS_DENIED")
          || text.contains("NT_STATUS_ACCOUNT_DISABLED")) {
        return new Result(Result.DENIED, List.of(),
            "That username and password didn't open it.");
      }
      return new Result(Result.UNREACHABLE, List.of(),
          "That device didn't answer. It may be asleep, or sharing may be switched off.");
    }

    return new Result(Result.OK, parse(text), null);
  }

  /**
   * Parse {@code smbclient -L … -g} output, which is the machine-readable
   * form: {@code Disk|Films|Movies and telly}. Falls back to the human
   * table when a build of smbclient ignores {@code -g}, because a parser
   * that works on one distro's samba build is not worth having.
   */
  static List<Share> parse(String output) {
    List<Share> shares = new ArrayList<>();
    for (String raw : output.split("\n")) {
      String line = raw.strip();
      if (line.isEmpty()) continue;

      if (line.startsWith("Disk|")) {
        String[] f = line.split("\\|", 3);
        if (f.length >= 2) add(shares, f[1], f.length > 2 ? f[2] : "");
        continue;
      }
      var m = ROW.matcher(raw);
      if (m.matches() && "Disk".equals(m.group(2))) {
        add(shares, m.group(1), m.group(3));
      }
    }
    return shares;
  }

  private static void add(List<Share> shares, String name, String comment) {
    String trimmed = name.strip();
    if (trimmed.isEmpty()) return;
    if (HIDDEN.contains(trimmed)) return;
    // Any $-suffixed share is administrative by convention.
    if (trimmed.endsWith("$")) return;
    shares.add(new Share(trimmed, comment == null || comment.isBlank() ? null : comment.strip()));
  }
}
