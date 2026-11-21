package org.yamcs.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.tctm.Link.Status;
import org.yamcs.tctm.TcDataLink;
import org.yamcs.utils.TimeEncoding;

public class MqttTcFrameLinkTest {

    @BeforeAll
    public static void setup() {
        // the mockup event producer timestamps its events
        TimeEncoding.setUp();
        EventProducerFactory.setMockup(true);
    }

    /**
     * A broker setting present but empty (an environment variable set to nothing): the link initialises and starts,
     * disabled at startup or not. Enabled, it does not connect, says why and fails a command at once.
     */
    @Test
    public void emptyBrokerDoesNotStopTheLink() throws Exception {
        Map<String, Object> vc = new HashMap<>();
        vc.put("vcId", 0);
        vc.put("service", "PACKET");
        vc.put("commandPostprocessorClassName", "org.yamcs.tctm.GenericCommandPostprocessor");
        vc.put("stream", "tc_realtime");
        vc.put("useCop1", false);
        Map<String, Object> m = new HashMap<>();
        m.put("name", "test");
        m.put("class", MqttTcFrameLink.class.getName());
        m.put("brokers", List.of(""));
        m.put("topic", "tc");
        m.put("spacecraftId", 0xAB);
        m.put("maxFrameLength", 1024);
        m.put("errorDetection", "CRC16");
        m.put("virtualChannels", List.of(vc));

        var link = new MqttTcFrameLink();
        link.init("test", "test", link.getSpec().validate(YConfiguration.wrap(m)));
        var cmdHist = mock(CommandHistoryPublisher.class);
        link.setCommandHistoryPublisher(cmdHist);
        var vcLink = (TcDataLink) link.getSubLinks().get(0);
        vcLink.setCommandHistoryPublisher(cmdHist);
        link.disable();
        link.startAsync().awaitRunning();
        assertEquals(Status.DISABLED, link.getLinkStatus());

        link.enable();
        assertEquals(Status.FAILED, link.getLinkStatus());
        assertEquals("no MQTT broker set", link.getDetailedStatus());
        assertTrue(EventProducerFactory.getMockupQueue().stream()
                .anyMatch(e -> e.getMessage().equals("Not connecting to MQTT: no MQTT broker set")));

        // a command still goes out through the virtual channel and fails at once for want of a broker
        var pc = mock(PreparedCommand.class);
        when(pc.getBinary()).thenReturn(new byte[] { 1, 2, 3 });
        assertTrue(vcLink.sendCommand(pc));
        verify(cmdHist, timeout(5000)).commandFailed(any(), anyLong(), eq("no MQTT broker set"));

        link.disable();
        link.stopAsync().awaitTerminated();
    }
}
