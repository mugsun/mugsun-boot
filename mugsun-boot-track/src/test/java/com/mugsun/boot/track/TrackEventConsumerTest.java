package com.mugsun.boot.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.mugsun.boot.auth.IpRegionService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/** 落库隔离：被库拒收的单条剔除，同批其他事件照常落库；连接类异常不拆批，原样抛出走整批重试 */
class TrackEventConsumerTest {

	private final TrackEventStore store = mock(TrackEventStore.class);
	private final TrackIngestMetrics metrics = mock(TrackIngestMetrics.class);
	private final TrackEventConsumer consumer = new TrackEventConsumer(store, mock(IpRegionService.class), metrics,
		mock(TrackAlertService.class), mock(TrackErrorIssueService.class));

	private static List<TrackIngestEvent> batch(String... eventIds) {
		List<TrackIngestEvent> events = new ArrayList<>();
		for (String id : eventIds) {
			TrackIngestEvent e = new TrackIngestEvent();
			e.setEventId(id);
			e.setAppKey("it-app");
			events.add(e);
		}
		return events;
	}

	@Test
	void rejectedRowsAreDroppedAndTheRestIsStored() {
		List<String> inserted = new ArrayList<>();
		doAnswer(inv -> {
			List<TrackIngestEvent> events = inv.getArgument(0);
			if (events.stream().anyMatch(e -> e.getEventId().startsWith("bad"))) {
				throw new DataIntegrityViolationException("batch rejected",
					new SQLException("invalid byte sequence for encoding \"UTF8\": 0x00", "22021"));
			}
			events.forEach(e -> inserted.add(e.getEventId()));
			return null;
		}).when(store).insertEvents(anyList());

		List<TrackIngestEvent> stored = consumer.persistEvents(batch("e1", "e2", "bad1", "e3", "e4", "e5", "bad2"));

		assertThat(inserted).containsExactly("e1", "e2", "e3", "e4", "e5");
		assertThat(stored).extracting(TrackIngestEvent::getEventId).containsExactly("e1", "e2", "e3", "e4", "e5");
		verify(metrics, times(2)).dropped("persist_failed", 1);
	}

	@Test
	void connectionErrorIsRethrownWithoutSplitting() {
		doThrow(new DataAccessResourceFailureException("db down", new SQLException("connection refused", "08001")))
			.when(store).insertEvents(anyList());

		assertThatThrownBy(() -> consumer.persistEvents(batch("e1", "e2", "e3")))
			.isInstanceOf(DataAccessResourceFailureException.class);
		verify(store, times(1)).insertEvents(anyList());
		verify(metrics, never()).dropped(anyString(), anyLong());
	}
}
