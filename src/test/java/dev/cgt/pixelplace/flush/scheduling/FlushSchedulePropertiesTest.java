package dev.cgt.pixelplace.flush.scheduling;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlushSchedulePropertiesTest {

    @Test
    void oneSecondFixedDelayIsAccepted() {
        FlushScheduleProperties properties = new FlushScheduleProperties(Duration.ofSeconds(1));

        assertEquals(Duration.ofSeconds(1), properties.fixedDelay());
    }

    @Test
    void nullZeroNegativeAndMillisecondOverflowAreRejected() {
        assertThrows(NullPointerException.class, () -> new FlushScheduleProperties(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FlushScheduleProperties(Duration.ZERO)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FlushScheduleProperties(Duration.ofNanos(-1))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FlushScheduleProperties(Duration.ofSeconds(Long.MAX_VALUE))
        );
    }

    @Test
    void onlyFixedDelayPropertyExistsWithoutEnabledOrCountThreshold() {
        List<String> componentNames = Arrays.stream(FlushScheduleProperties.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("fixedDelay"), componentNames);
    }
}
