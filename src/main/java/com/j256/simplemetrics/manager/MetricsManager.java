package com.j256.simplemetrics.manager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.j256.simplemetrics.metric.ControlledMetric;
import com.j256.simplemetrics.metric.MetricValueDetails;
import com.j256.simplemetrics.persister.MetricDetailsPersister;
import com.j256.simplemetrics.persister.MetricValuesPersister;
import com.j256.simplemetrics.utils.MiscUtils;

/**
 * Class which manages the various metrics that are in the system so they can be queried by operations. You register
 * metrics with this class, register classes that need to manually update metrics values, and controls the metrics
 * persistence.
 * 
 * @author graywatson
 */
public class MetricsManager {

	private MetricValuesPersister[] metricValuesPersisters = new MetricValuesPersister[0];
	private MetricDetailsPersister[] metricDetailsPersisters = new MetricDetailsPersister[0];

	private final List<ControlledMetric<?, ?>> metrics = new ArrayList<ControlledMetric<?, ?>>();
	private final List<MetricsUpdater> metricsUpdaters = new ArrayList<MetricsUpdater>();
	private final List<MetricsRegisterListener> registerListeners = new ArrayList<MetricsRegisterListener>();
	private int persistCount;

	/**
	 * Register a metric with the manager.
	 */
	public void registerMetric(ControlledMetric<?, ?> metric) {
		synchronized (metrics) {
			metrics.add(metric);
		}
		for (MetricsRegisterListener registerListener : registerListeners) {
			registerListener.metricRegistered(metric);
		}
	}

	/**
	 * Unregister a metric with the manager.
	 */
	public void unregisterMetric(ControlledMetric<?, ?> metric) {
		boolean removed;
		synchronized (metrics) {
			removed = metrics.remove(metric);
		}
		if (removed) {
			for (MetricsRegisterListener registerListener : registerListeners) {
				registerListener.metricUnregistered(metric);
			}
		}
	}

	/**
	 * Register a {@link MetricsUpdater} to be called right before persist writes the metrics.
	 */
	public void registerUpdater(MetricsUpdater metricsUpdater) {
		synchronized (metricsUpdaters) {
			metricsUpdaters.add(metricsUpdater);
		}
	}

	/**
	 * Register a listener for metrics registered and unregistered.
	 */
	public void registerRegisterListener(MetricsRegisterListener registerListener) {
		synchronized (registerListener) {
			registerListeners.add(registerListener);
		}
	}

	/**
	 * Persists the configured metrics by calling to the registered updaters, extracting the value-details from the
	 * metrics, and then calling the registered value and details persisters.
	 */
	public void persist() throws IOException {

		// update the metric values if necessary
		updateMetrics();

		// if we aren't persisting details then this is easy
		if (metricDetailsPersisters.length == 0) {
			persistValuesOnly();
			return;
		}

		// first we make a map of metric -> details for the persisters
		long timeCollectedMillis = System.currentTimeMillis();
		Map<ControlledMetric<?, ?>, MetricValueDetails> metricValueDetailMap;
		synchronized (metrics) {
			metricValueDetailMap = new HashMap<ControlledMetric<?, ?>, MetricValueDetails>(metrics.size());
			for (ControlledMetric<?, ?> metric : metrics) {
				metricValueDetailMap.put(metric, metric.getValueDetailsToPersist());
			}
		}

		// if we have value persisters then extract the values from the details map
		Map<ControlledMetric<?, ?>, Number> metricValueMap = null;
		if (metricValuesPersisters.length > 0) {
			metricValueMap = new HashMap<ControlledMetric<?, ?>, Number>(metricValueDetailMap.size());
			for (Entry<ControlledMetric<?, ?>, MetricValueDetails> entry : metricValueDetailMap.entrySet()) {
				metricValueMap.put(entry.getKey(), entry.getValue().getValue());
			}
			metricValueMap = Collections.unmodifiableMap(metricValueMap);
		}
		metricValueDetailMap = Collections.unmodifiableMap(metricValueDetailMap);

		Exception wasThrown = null;
		for (MetricValuesPersister persister : metricValuesPersisters) {
			try {
				persister.persist(metricValueMap, timeCollectedMillis);
			} catch (Exception e) {
				// hold any exceptions thrown by them so we can get through all persisters
				wasThrown = e;
			}
		}
		for (MetricDetailsPersister persister : metricDetailsPersisters) {
			try {
				persister.persist(metricValueDetailMap, timeCollectedMillis);
			} catch (Exception e) {
				// hold any exceptions thrown by them so we can get through all persisters
				wasThrown = e;
			}
		}
		persistCount++;
		if (wasThrown != null) {
			if (wasThrown instanceof IOException) {
				throw (IOException) wasThrown;
			} else {
				throw new IOException(wasThrown);
			}
		}
	}

	/**
	 * Persists the configured metrics to the value persisters <i>only</i> by extracting the values from the metrics and
	 * then calling the registered persisters. Usually you will want to call {@link #persist()} instead.
	 * 
	 * <p>
	 * <b>NOTE:</b> you should call updateMetrics() before calling this method if necessary.
	 * </p>
	 */
	public void persistValuesOnly() throws IOException {

		// first we make a unmodifiable map of metric -> persisted value for the persisters
		long timeCollectedMillis = System.currentTimeMillis();
		Map<ControlledMetric<?, ?>, Number> metricValues;
		synchronized (metrics) {
			metricValues = new HashMap<ControlledMetric<?, ?>, Number>(metrics.size());
			for (ControlledMetric<?, ?> metric : metrics) {
				metricValues.put(metric, metric.getValueToPersist());
			}
		}
		metricValues = Collections.unmodifiableMap(metricValues);

		Exception wasThrown = null;
		for (MetricValuesPersister persister : metricValuesPersisters) {
			try {
				persister.persist(metricValues, timeCollectedMillis);
			} catch (Exception e) {
				// hold any exceptions thrown by them so we can get through all persisters
				wasThrown = e;
			}
		}
		persistCount++;
		if (wasThrown != null) {
			if (wasThrown instanceof IOException) {
				throw (IOException) wasThrown;
			} else {
				throw new IOException(wasThrown);
			}
		}
	}

	/**
	 * Return a map of the controlled metrics and their current associated values.
	 * 
	 * NOTE: this does not call {@link #updateMetrics()} beforehand.
	 */
	public Map<ControlledMetric<?, ?>, Number> getMetricValuesMap() {
		synchronized (metrics) {
			Map<ControlledMetric<?, ?>, Number> metricValues =
					new HashMap<ControlledMetric<?, ?>, Number>(metrics.size());
			for (ControlledMetric<?, ?> metric : metrics) {
				Number value = metric.getValue();
				// convert the value to a long if possible
				if (value.doubleValue() == value.longValue()) {
					value = value.longValue();
				}
				metricValues.put(metric, value);
			}
			return metricValues;
		}
	}

	/**
	 * Return a map of the controlled metrics and their current associated values.
	 * 
	 * NOTE: this does not call {@link #updateMetrics()} beforehand.
	 */
	public Map<ControlledMetric<?, ?>, MetricValueDetails> getMetricValueDetailsMap() {
		synchronized (metrics) {
			Map<ControlledMetric<?, ?>, MetricValueDetails> metricValueDetails =
					new HashMap<ControlledMetric<?, ?>, MetricValueDetails>(metrics.size());
			for (ControlledMetric<?, ?> metric : metrics) {
				metricValueDetails.put(metric, metric.getValueDetails());
			}
			return metricValueDetails;
		}
	}

	/**
	 * Update the various classes' metrics.
	 */
	public void updateMetrics() {
		synchronized (metricsUpdaters) {
			// call our classes to update their stats
			for (MetricsUpdater metricsUpdater : metricsUpdaters) {
				metricsUpdater.updateMetrics();
			}
		}
	}

	/**
	 * @return An unmodifiable collection of metrics we are managing.
	 */
	public Collection<ControlledMetric<?, ?>> getMetrics() {
		synchronized (metrics) {
			return Collections.unmodifiableList(metrics);
		}
	}

	/**
	 * Set the persisters for the metric values.
	 */
	// @NotRequired("Default is a value or value-details persister")
	public void setMetricValuesPersisters(MetricValuesPersister[] metricValuesPersisters) {
		this.metricValuesPersisters = metricValuesPersisters;
	}

	/**
	 * Set the persisters for the metric details.
	 */
	// @NotRequired("Default is a value or value-details persister")
	public void setMetricDetailsPersisters(MetricDetailsPersister[] metricDetailsPersisters) {
		this.metricDetailsPersisters = metricDetailsPersisters;
	}
	

	
	public String[] getMetricValues() {
		// update the metrics
		updateMetrics();
		List<String> values;
		synchronized (metrics) {
			values = new ArrayList<String>(metrics.size());
			for (ControlledMetric<?, ?> metric : metrics) {
				values.add(MiscUtils.metricToString(metric) + "=" + metric.getValue());
			}
		}
		return values.toArray(new String[values.size()]);
	}

	public int getPersistCount() {
		return persistCount;
	}
}
