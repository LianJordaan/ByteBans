package io.github.lianjordaan.byteBans.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Normalizes literal IP addresses without performing hostname lookups. */
public final class IpAddresses {
    private IpAddresses() {
    }

    public static String canonical(String input) {
        if (input == null || input.isBlank() || input.length() > 45) {
            throw new IllegalArgumentException("Enter a literal IPv4 or IPv6 address");
        }
        if (!input.contains(":")) {
            String[] parts = input.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Enter a literal IPv4 or IPv6 address");
            int[] octets = new int[4];
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("[0-9]{1,3}")) {
                    throw new IllegalArgumentException("Enter a literal IPv4 or IPv6 address");
                }
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] > 255) throw new IllegalArgumentException("Invalid IPv4 address");
            }
            return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
        }
        if (!input.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalArgumentException("Enter a literal IPv4 or IPv6 address");
        }
        try {
            InetAddress address = InetAddress.getByName(input);
            if (!(address instanceof Inet6Address)) {
                throw new IllegalArgumentException("Invalid IPv6 address");
            }
            return address.getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IPv6 address", e);
        }
    }
}
