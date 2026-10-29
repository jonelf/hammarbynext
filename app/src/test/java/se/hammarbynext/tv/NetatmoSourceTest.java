package se.hammarbynext.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class NetatmoSourceTest {

    private static final String STATION = "{\"body\":{\"devices\":[{"
            + "\"dashboard_data\":{\"Temperature\":12.6,\"Humidity\":57,\"CO2\":620},"
            + "\"modules\":["
            + "{\"type\":\"NAModule1\",\"reachable\":true,\"dashboard_data\":{\"Temperature\":4.9,\"Humidity\":97,"
            + "\"min_temp\":-0.1,\"max_temp\":10.5,\"temp_trend\":\"down\"}},"
            + "{\"type\":\"NAModule3\",\"reachable\":true,\"dashboard_data\":{\"Rain\":0,\"sum_rain_24\":0.1}}"
            + "]}]}}";

    /** Fukt ute och inne på samma ställe, i formatet "Fukt ute 97% inne 57%". */
    @Test
    public void humidityOutsideAndInside() throws Exception {
        NetatmoSource.Row row = NetatmoSource.parse(STATION);
        assertEquals("Torpet", row.topLine);
        assertEquals("4,9° ↓", row.headline);
        assertEquals("Min -0,1°  Max 10,5°  ·  Fukt ute 97% inne 57%  ·  Inne 12,6°  ·  Regn 0,1 mm", row.detail);
    }

    @Test
    public void humidityWhenOneIsMissing() {
        assertEquals("Fukt ute 97%", NetatmoSource.humidity(97, null));
        assertEquals("Fukt inne 57%", NetatmoSource.humidity(null, 57));
        assertNull(NetatmoSource.humidity(null, null));
    }
}
