package com.j256.simplemetrics.metric;

import java.util.concurrent.atomic.AtomicLong;

import com.j256.simplejmx.common.JmxResource;
import com.j256.simplemetrics.metric.ControlledMetricAccum.AccumValue;

/**
 * Managed {@link ControlledMetric} for metrics like page-count or database-accesses that you are adding to continually
 * as opposed to a {@link ControlledMetricValue}.
 * 
 * @author graywatson
 */
@JmxResource(domainName = "com.j256", description = "Controlled Accumulator")
public class ControlledMetricAccum extends BaseControlledMetric<Long, AccumValue> {

	// We have this intermediate counter because we want to not have every increment cause another metric value object
	private final AtomicLong counter = new AtomicLong();

	/**
	 * @param component
	 *            Component short name such as "my". Required.
	 * @param module
	 *            Module name to identify the part of the component such as "pageview". Null if none.
	 * @param name
	 *            String label description the metric. Required.
	 * @param description
	 *            Description for more information which may not be persisted.
	 * @param unit
	 *            Unit of the metric. Null if none.
	 */
	public ControlledMetricAccum(String component, String module, String name, String description, String unit) {
		super(component, module, name, description, unit);
	}

	@Override
	public AccumValue createInitialValue() {
		return new AccumValue(0, true);
	}

	@Override
	public Long makeValueFromLong(long value) {
		return Long.valueOf(value);
	}

	@Override
	public Long makeValueFromNumber(Number value) {
		return value.longValue();
	}

	/**
	 * Add a delta value to the metric. This is for metrics (like pageview count) which are incrementing over time.
	 */
	public long add(long delta) {
		return counter.addAndGet(delta);
	}

	/**
	 * Add one to the metric.
	 */
	public long increment() {
		return counter.incrementAndGet();
	}

	@Override
	public void adjustValue(long value) {
		// we overload this so so we don't generate a new object on every adjustment
		counter.addAndGet(value);
	}

	@Override
	public void adjustValue(Number value) {
		// we overload this so so we don't generate a new object on every adjustment
		counter.addAndGet(value.longValue());
	}

	@Override
	public Number getValue() {
		adjustValue();
		return super.getValue();
	}

	@Override
	public MetricValueDetails getValueDetails() {
		adjustValue();
		return super.getValueDetails();
	}

	@Override
	public Number getValueToPersist() {
		adjustValue();
		return super.getValueToPersist();
	}

	@Override
	public MetricValueDetails getValueDetailsToPersist() {
		adjustValue();
		return super.getValueDetailsToPersist();
	}

	@Override
	public AggregationType getAggregationType() {
		return AggregationType.SUM;
	}

	private void adjustValue() {
		long value = counter.getAndSet(0);
		if (value > 0) {
			// we adjust here only when the value is needed so we don't generate a new object on every adjustment
			super.adjustValue(value);
		}
	}

	/**
	 * Wrapper around a long counter and a reset flag.
	 */
	public static class AccumValue implements MetricValue<Long, AccumValue> {
		private final long value;
		private final boolean resetNext;

		AccumValue(long value, boolean resetNext) {
			this.value = value;
			this.resetNext = resetNext;
		}

		@Override
		public AccumValue makeResetNext() {
			return new AccumValue(value, true);
		}

		@Override
		public AccumValue makeAdjusted(Long newValue) {
			if (resetNext) {
				return new AccumValue(newValue, false);
			} else {
				return new AccumValue(this.value + newValue, false);
			}
		}

		@Override
		public Number getValue() {
			return Long.valueOf(value);
		}

		@Override
		public int getNumSamples() {
			// not 100% sure this is right but the count is the number of samples.
			if (value >= Integer.MAX_VALUE) {
				return Integer.MAX_VALUE;
			} else {
				return (int) value;
			}
		}

		@Override
		public Number getMin() {
			// with an accumulator, the min/max is just the count
			return Long.valueOf(value);
		}

		@Override
		public Number getMax() {
			// with an accumulator, the min/max is just the count
			return Long.valueOf(value);
		}
	}
}
