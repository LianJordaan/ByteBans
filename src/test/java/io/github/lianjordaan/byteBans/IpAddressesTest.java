package io.github.lianjordaan.byteBans;

import io.github.lianjordaan.byteBans.util.DatabaseUtils;
import io.github.lianjordaan.byteBans.util.IpAddresses;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class IpAddressesTest {
    @Test
    void acceptsLiteralAddressesButNeverHostnamesOrInvalidOctets() {
        assertEquals("192.168.0.155", IpAddresses.canonical("192.168.0.155"));
        assertEquals(IpAddresses.canonical("2001:db8::1"),
                IpAddresses.canonical("2001:0DB8:0:0:0:0:0:1"));
        assertThrows(IllegalArgumentException.class, () -> IpAddresses.canonical("example.com"));
        assertThrows(IllegalArgumentException.class, () -> IpAddresses.canonical("256.1.1.1"));
        assertThrows(IllegalArgumentException.class, () -> IpAddresses.canonical("2001:db8::1%eth0"));
    }

    @Test
    void remembersOnlyTheMostRecentAddressForAnExistingPlayer() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseUtils.migrate(connection, "bytebans_", false);
            String uuid = "1a567205-91c5-4ec1-a6e4-eb395393528f";
            DatabaseUtils.savePlayerAddress(connection, "bytebans_", false, uuid, "192.168.0.1");
            DatabaseUtils.savePlayerAddress(connection, "bytebans_", false, uuid, "192.168.0.2");
            assertEquals("192.168.0.2", DatabaseUtils.getPlayerAddress(connection, "bytebans_", uuid));
            assertNull(DatabaseUtils.getPlayerAddress(connection, "bytebans_", "missing"));
        }
    }
}
