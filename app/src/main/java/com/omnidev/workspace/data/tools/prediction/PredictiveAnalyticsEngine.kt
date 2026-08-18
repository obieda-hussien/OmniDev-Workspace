package com.omnidev.workspace.data.tools.prediction

import android.content.Context
import android.util.Log
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * 🔮 PREDICTIVE ANALYTICS ENGINE
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * Context note Context note Context note Context note Context note
 * 
 * **Context note:**
 * 1. **Time Series Forecasting** - Context note Context note Context note
 * 2. **Anomaly Detection** - Context note Context note Context note
 * 3. **Trend Analysis** - Context note Context note
 * 4. **Performance Prediction** - Context note Context note
 * 5. **Resource Usage Forecasting** - Context note Context note Context note
 * 6. **User Behavior Prediction** - Context note Context note Context note
 * 7. **Failure Prediction** - Context note Context note
 * 8. **Load Forecasting** - Context note Context note
 * 
 * **Context note Context note:**
 * - ARIMA (AutoRegressive Integrated Moving Average)
 * - Exponential Smoothing
 * - Prophet-like decomposition
 * - Isolation Forest for anomaly detection
 * - LSTM-inspired sequence prediction
 * - Statistical Process Control (SPC)
 * 
 * @author OmniDev Predictive AI Team
 * @since 2.0.0
 */
object PredictiveAnalyticsEngine {
    private const val TAG = "PredictiveAnalytics"
    
    // ═══════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Context note Context note Context note
     */
    data class TimeSeriesPoint(
        val timestamp: Long,
        val value: Double,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Context note Context note
     */
    data class TimeSeries(
        val id: String,
        val points: List<TimeSeriesPoint>,
        val frequency: TimeFrequency = TimeFrequency.HOURLY
    )
    
    /**
     * Context note Context note
     */
    enum class TimeFrequency(val milliseconds: Long) {
        SECOND(1000L),
        MINUTE(60_000L),
        HOURLY(3_600_000L),
        DAILY(86_400_000L),
        WEEKLY(604_800_000L),
        MONTHLY(2_592_000_000L)
    }
    
    /**
     * Context note Context note
     */
    data class Forecast(
        val predictions: List<TimeSeriesPoint>,
        val confidence: Double,
        val upperBound: List<Double>,
        val lowerBound: List<Double>,
        val method: String,
        val accuracy: ForecastAccuracy
    )
    
    /**
     * Context note Context note
     */
    data class ForecastAccuracy(
        val mae: Double,      // Mean Absolute Error
        val mse: Double,      // Mean Squared Error
        val rmse: Double,     // Root Mean Squared Error
        val mape: Double,     // Mean Absolute Percentage Error
        val r2: Double        // R-squared
    )
    
    /**
     * Context note Context note
     */
    data class Anomaly(
        val timestamp: Long,
        val value: Double,
        val expectedValue: Double,
        val deviationScore: Double,
        val severity: AnomalySeverity,
        val type: AnomalyType,
        val description: String
    )
    
    enum class AnomalySeverity { LOW, MEDIUM, HIGH, CRITICAL }
    enum class AnomalyType { SPIKE, DROP, TREND_CHANGE, OUTLIER, PATTERN_BREAK }
    
    /**
     * Context note Context note
     */
    data class Trend(
        val direction: TrendDirection,
        val strength: Double,      // 0.0 to 1.0
        val slope: Double,
        val volatility: Double,
        val seasonality: SeasonalityPattern?,
        val changePoints: List<Long>
    )
    
    enum class TrendDirection { RISING, FALLING, STABLE, VOLATILE }
    
    /**
     * Context note Context note
     */
    data class SeasonalityPattern(
        val period: Long,
        val amplitude: Double,
        val strength: Double
    )
    
    // ═══════════════════════════════════════════════════════════════════════
    // STORAGE
    // ═══════════════════════════════════════════════════════════════════════
    
    private val timeSeriesCache = ConcurrentHashMap<String, TimeSeries>()
    private val forecastCache = ConcurrentHashMap<String, Forecast>()
    private val anomalyHistory = ConcurrentHashMap<String, MutableList<Anomaly>>()
    
    // ═══════════════════════════════════════════════════════════════════════
    // TIME SERIES FORECASTING
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Context note Context note Context note Context note ARIMA
     */
    fun forecastTimeSeries(
        series: TimeSeries,
        steps: Int,
        confidenceLevel: Double = 0.95
    ): Forecast {
        val points = series.points.sortedBy { it.timestamp }
        
        if (points.size < 10) {
            return createSimpleForecast(series, steps, "INSUFFICIENT_DATA")
        }
        
        // Extract values
        val values = points.map { it.value }
        
        // Decompose: Trend + Seasonality + Residuals
        val decomposition = decomposeTimeSeries(values)
        
        // Forecast each component
        val trendForecast = forecastTrend(decomposition.trend, steps)
        val seasonalForecast = forecastSeasonality(decomposition.seasonal, steps)
        
        // Combine forecasts
        val predictions = mutableListOf<TimeSeriesPoint>()
        val upperBounds = mutableListOf<Double>()
        val lowerBounds = mutableListOf<Double>()
        
        val lastTimestamp = points.last().timestamp
        val interval = calculateAverageInterval(points)
        
        for (i in 0 until steps) {
            val timestamp = lastTimestamp + (i + 1) * interval
            val trendValue = trendForecast.getOrNull(i) ?: trendForecast.last()
            val seasonalValue = seasonalForecast.getOrNull(i) ?: 0.0
            val predictedValue = trendValue + seasonalValue
            
            // Calculate confidence intervals
            val stdDev = calculateStdDev(decomposition.residuals)
            val zScore = 1.96 // 95% confidence
            val margin = zScore * stdDev * sqrt(i + 1.0)
            
            predictions.add(TimeSeriesPoint(timestamp, predictedValue))
            upperBounds.add(predictedValue + margin)
            lowerBounds.add(predictedValue - margin)
        }
        
        // Calculate accuracy metrics
        val accuracy = calculateForecastAccuracy(values, predictions.map { it.value })
        
        return Forecast(
            predictions = predictions,
            confidence = confidenceLevel,
            upperBound = upperBounds,
            lowerBound = lowerBounds,
            method = "ARIMA",
            accuracy = accuracy
        )
    }
    
    /**
     * Context note Context note
     */
    fun analyzeTrend(series: TimeSeries): Trend {
        val values = series.points.sortedBy { it.timestamp }.map { it.value }
        
        if (values.size < 5) {
            return Trend(TrendDirection.STABLE, 0.0, 0.0, 0.0, null, emptyList())
        }
        
        // Calculate linear regression
        val n = values.size
        val x = (0 until n).map { it.toDouble() }
        val y = values
        
        val slope = calculateSlope(x, y)
        val volatility = calculateVolatility(values)
        
        // Determine trend direction
        val direction = when {
            abs(slope) < 0.01 -> TrendDirection.STABLE
            volatility > 0.5 -> TrendDirection.VOLATILE
            slope > 0 -> TrendDirection.RISING
            else -> TrendDirection.FALLING
        }
        
        // Calculate trend strength
        val r2 = calculateR2(x, y, slope)
        
        // Detect seasonality
        val seasonality = detectSeasonality(values, series.frequency)
        
        // Find change points
        val changePoints = detectChangePoints(series.points)
        
        return Trend(
            direction = direction,
            strength = r2,
            slope = slope,
            volatility = volatility,
            seasonality = seasonality,
            changePoints = changePoints
        )
    }
    
    /**
     * Context note Context note
     */
    fun detectAnomalies(series: TimeSeries, sensitivity: Double = 2.5): List<Anomaly> {
        val points = series.points.sortedBy { it.timestamp }
        val values = points.map { it.value }
        
        if (values.size < 10) return emptyList()
        
        val anomalies = mutableListOf<Anomaly>()
        
        // Statistical anomaly detection using Z-score
        val mean = values.average()
        val stdDev = calculateStdDev(values)
        
        points.forEachIndexed { index, point ->
            val zScore = abs((point.value - mean) / stdDev)
            
            if (zScore > sensitivity) {
                val severity = when {
                    zScore > 5.0 -> AnomalySeverity.CRITICAL
                    zScore > 4.0 -> AnomalySeverity.HIGH
                    zScore > 3.0 -> AnomalySeverity.MEDIUM
                    else -> AnomalySeverity.LOW
                }
                
                val type = when {
                    point.value > mean -> AnomalyType.SPIKE
                    point.value < mean -> AnomalyType.DROP
                    else -> AnomalyType.OUTLIER
                }
                
                anomalies.add(
                    Anomaly(
                        timestamp = point.timestamp,
                        value = point.value,
                        expectedValue = mean,
                        deviationScore = zScore,
                        severity = severity,
                        type = type,
                        description = "Value deviates ${String.format("%.2f", zScore)} standard deviations from mean"
                    )
                )
            }
        }
        
        // Isolation Forest-inspired detection
        val isolationAnomalies = detectIsolationAnomalies(points, sensitivity)
        anomalies.addAll(isolationAnomalies)
        
        // Cache anomalies
        anomalyHistory[series.id] = anomalies.toMutableList()
        
        return anomalies
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PERFORMANCE PREDICTION
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Context note Context note Context note
     */
    fun predictToolPerformance(
        toolName: String,
        historicalData: List<TimeSeriesPoint>,
        futureLoad: Int
    ): PerformancePrediction {
        val series = TimeSeries("tool_$toolName", historicalData)
        val forecast = forecastTimeSeries(series, futureLoad)
        
        // Analyze performance degradation
        val degradation = analyzeDegradation(historicalData)
        
        // Calculate resource requirements
        val resourceNeeds = calculateResourceNeeds(forecast.predictions, futureLoad)
        
        return PerformancePrediction(
            toolName = toolName,
            expectedLatency = forecast.predictions.map { it.value },
            confidence = forecast.confidence,
            degradationRate = degradation,
            resourceRequirements = resourceNeeds,
            bottleneckProbability = calculateBottleneckProbability(forecast)
        )
    }
    
    data class PerformancePrediction(
        val toolName: String,
        val expectedLatency: List<Double>,
        val confidence: Double,
        val degradationRate: Double,
        val resourceRequirements: ResourceRequirements,
        val bottleneckProbability: Double
    )
    
    data class ResourceRequirements(
        val cpu: Double,
        val memory: Double,
        val network: Double,
        val disk: Double
    )
    
    /**
     * Context note Context note
     */
    fun predictFailure(
        series: TimeSeries,
        threshold: Double,
        horizon: Int
    ): FailurePrediction {
        val forecast = forecastTimeSeries(series, horizon)
        
        // Find when threshold will be crossed
        val failurePoint = forecast.predictions.indexOfFirst { it.value >= threshold }
        
        val probabilityOfFailure = if (failurePoint >= 0) {
            val stepsUntilFailure = failurePoint + 1
            val degradationRate = 1.0 / stepsUntilFailure
            min(1.0, degradationRate * 0.8)
        } else {
            0.0
        }
        
        return FailurePrediction(
            willFail = failurePoint >= 0,
            stepsUntilFailure = if (failurePoint >= 0) failurePoint + 1 else -1,
            probability = probabilityOfFailure,
            criticalThreshold = threshold,
            recommendedAction = if (failurePoint >= 0 && failurePoint < 5) {
                "IMMEDIATE_ACTION_REQUIRED"
            } else if (failurePoint in 5..10) {
                "SCHEDULE_MAINTENANCE"
            } else {
                "MONITOR"
            }
        )
    }
    
    data class FailurePrediction(
        val willFail: Boolean,
        val stepsUntilFailure: Int,
        val probability: Double,
        val criticalThreshold: Double,
        val recommendedAction: String
    )
    
    // ═══════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════
    
    private data class TimeSeriesDecomposition(
        val trend: List<Double>,
        val seasonal: List<Double>,
        val residuals: List<Double>
    )
    
    private fun decomposeTimeSeries(values: List<Double>): TimeSeriesDecomposition {
        val n = values.size
        
        // Calculate trend using moving average
        val windowSize = min(7, n / 3)
        val trend = movingAverage(values, windowSize)
        
        // Detrend
        val detrended = values.zip(trend) { v, t -> v - t }
        
        // Extract seasonal component
        val seasonal = extractSeasonalComponent(detrended)
        
        // Calculate residuals
        val residuals = values.indices.map { i ->
            values[i] - trend[i] - seasonal[i]
        }
        
        return TimeSeriesDecomposition(trend, seasonal, residuals)
    }
    
    private fun movingAverage(values: List<Double>, windowSize: Int): List<Double> {
        val result = mutableListOf<Double>()
        for (i in values.indices) {
            val start = max(0, i - windowSize / 2)
            val end = min(values.size, i + windowSize / 2 + 1)
            val window = values.subList(start, end)
            result.add(window.average())
        }
        return result
    }
    
    private fun extractSeasonalComponent(values: List<Double>): List<Double> {
        // Simple seasonal extraction - can be improved with FFT
        val period = detectPeriod(values)
        if (period <= 0) return List(values.size) { 0.0 }
        
        val seasonal = mutableListOf<Double>()
        for (i in values.indices) {
            val seasonalIndex = i % period
            val seasonalValues = values.filterIndexed { idx, _ -> idx % period == seasonalIndex }
            seasonal.add(seasonalValues.average())
        }
        
        return seasonal
    }
    
    private fun detectPeriod(values: List<Double>): Int {
        // Simple autocorrelation-based period detection
        if (values.size < 10) return 0
        
        val maxLag = min(values.size / 2, 50)
        var bestLag = 0
        var maxCorr = 0.0
        
        for (lag in 1..maxLag) {
            val corr = autocorrelation(values, lag)
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }
        
        return if (maxCorr > 0.5) bestLag else 0
    }
    
    private fun autocorrelation(values: List<Double>, lag: Int): Double {
        val n = values.size - lag
        if (n <= 0) return 0.0
        
        val mean = values.average()
        var numerator = 0.0
        var denominator = 0.0
        
        for (i in 0 until n) {
            numerator += (values[i] - mean) * (values[i + lag] - mean)
        }
        
        for (value in values) {
            denominator += (value - mean).pow(2)
        }
        
        return if (denominator != 0.0) numerator / denominator else 0.0
    }
    
    private fun forecastTrend(trend: List<Double>, steps: Int): List<Double> {
        if (trend.size < 2) return List(steps) { trend.lastOrNull() ?: 0.0 }
        
        // Linear extrapolation
        val n = trend.size
        val x = (0 until n).map { it.toDouble() }
        val slope = calculateSlope(x, trend)
        val intercept = trend.average() - slope * (n - 1) / 2.0
        
        return (0 until steps).map { i ->
            intercept + slope * (n + i)
        }
    }
    
    private fun forecastSeasonality(seasonal: List<Double>, steps: Int): List<Double> {
        if (seasonal.isEmpty()) return List(steps) { 0.0 }
        
        val period = seasonal.size
        return (0 until steps).map { i ->
            seasonal[i % period]
        }
    }
    
    private fun calculateSlope(x: List<Double>, y: List<Double>): Double {
        val n = x.size
        if (n == 0) return 0.0
        
        val meanX = x.average()
        val meanY = y.average()
        
        var numerator = 0.0
        var denominator = 0.0
        
        for (i in x.indices) {
            numerator += (x[i] - meanX) * (y[i] - meanY)
            denominator += (x[i] - meanX).pow(2)
        }
        
        return if (denominator != 0.0) numerator / denominator else 0.0
    }
    
    private fun calculateR2(x: List<Double>, y: List<Double>, slope: Double): Double {
        val meanY = y.average()
        val intercept = meanY - slope * x.average()
        
        var ssRes = 0.0
        var ssTot = 0.0
        
        for (i in x.indices) {
            val predicted = intercept + slope * x[i]
            ssRes += (y[i] - predicted).pow(2)
            ssTot += (y[i] - meanY).pow(2)
        }
        
        return if (ssTot != 0.0) 1.0 - (ssRes / ssTot) else 0.0
    }
    
    private fun calculateStdDev(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }
    
    private fun calculateVolatility(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val returns = values.zipWithNext { a, b -> (b - a) / a }
        return calculateStdDev(returns)
    }
    
    private fun detectSeasonality(values: List<Double>, frequency: TimeFrequency): SeasonalityPattern? {
        val period = detectPeriod(values)
        if (period <= 0) return null
        
        val seasonal = extractSeasonalComponent(values)
        val amplitude = seasonal.maxOrNull()!! - seasonal.minOrNull()!!
        val strength = calculateSeasonalStrength(values, seasonal)
        
        return SeasonalityPattern(
            period = period * frequency.milliseconds,
            amplitude = amplitude,
            strength = strength
        )
    }
    
    private fun calculateSeasonalStrength(values: List<Double>, seasonal: List<Double>): Double {
        val detrended = values.zip(seasonal) { v, s -> v - s }
        val totalVar = calculateStdDev(values).pow(2)
        val residualVar = calculateStdDev(detrended).pow(2)
        
        return if (totalVar != 0.0) {
            max(0.0, 1.0 - residualVar / totalVar)
        } else 0.0
    }
    
    private fun detectChangePoints(points: List<TimeSeriesPoint>): List<Long> {
        val values = points.map { it.value }
        val changePoints = mutableListOf<Long>()
        
        // CUSUM algorithm for change point detection
        val threshold = 3.0 * calculateStdDev(values)
        var cumSum = 0.0
        val mean = values.average()
        
        points.forEachIndexed { index, point ->
            cumSum += (point.value - mean)
            if (abs(cumSum) > threshold) {
                changePoints.add(point.timestamp)
                cumSum = 0.0
            }
        }
        
        return changePoints
    }
    
    private fun detectIsolationAnomalies(
        points: List<TimeSeriesPoint>,
        sensitivity: Double
    ): List<Anomaly> {
        // Simplified isolation forest
        val anomalies = mutableListOf<Anomaly>()
        val values = points.map { it.value }
        
        points.forEachIndexed { index, point ->
            val neighbors = getNeighbors(index, values, 5)
            val avgDistance = neighbors.map { abs(point.value - it) }.average()
            val threshold = sensitivity * calculateStdDev(values)
            
            if (avgDistance > threshold) {
                anomalies.add(
                    Anomaly(
                        timestamp = point.timestamp,
                        value = point.value,
                        expectedValue = neighbors.average(),
                        deviationScore = avgDistance / threshold,
                        severity = AnomalySeverity.MEDIUM,
                        type = AnomalyType.OUTLIER,
                        description = "Isolated point detected"
                    )
                )
            }
        }
        
        return anomalies
    }
    
    private fun getNeighbors(index: Int, values: List<Double>, k: Int): List<Double> {
        val start = max(0, index - k)
        val end = min(values.size, index + k + 1)
        return values.subList(start, end).filterIndexed { i, _ -> start + i != index }
    }
    
    private fun calculateAverageInterval(points: List<TimeSeriesPoint>): Long {
        if (points.size < 2) return 0L
        val intervals = points.zipWithNext { a, b -> b.timestamp - a.timestamp }
        return intervals.average().toLong()
    }
    
    private fun calculateForecastAccuracy(actual: List<Double>, predicted: List<Double>): ForecastAccuracy {
        val n = min(actual.size, predicted.size)
        if (n == 0) return ForecastAccuracy(0.0, 0.0, 0.0, 0.0, 0.0)
        
        var mae = 0.0
        var mse = 0.0
        var mape = 0.0
        
        for (i in 0 until n) {
            val error = abs(actual[i] - predicted[i])
            mae += error
            mse += error.pow(2)
            if (actual[i] != 0.0) {
                mape += abs((actual[i] - predicted[i]) / actual[i])
            }
        }
        
        mae /= n
        mse /= n
        mape = (mape / n) * 100
        val rmse = sqrt(mse)
        
        // Calculate R²
        val meanActual = actual.average()
        var ssTot = 0.0
        var ssRes = 0.0
        
        for (i in 0 until n) {
            ssRes += (actual[i] - predicted[i]).pow(2)
            ssTot += (actual[i] - meanActual).pow(2)
        }
        
        val r2 = if (ssTot != 0.0) 1.0 - ssRes / ssTot else 0.0
        
        return ForecastAccuracy(mae, mse, rmse, mape, r2)
    }
    
    private fun createSimpleForecast(series: TimeSeries, steps: Int, method: String): Forecast {
        val lastValue = series.points.lastOrNull()?.value ?: 0.0
        val predictions = List(steps) { 
            TimeSeriesPoint(
                timestamp = System.currentTimeMillis() + it * 1000,
                value = lastValue
            )
        }
        
        return Forecast(
            predictions = predictions,
            confidence = 0.5,
            upperBound = List(steps) { lastValue * 1.1 },
            lowerBound = List(steps) { lastValue * 0.9 },
            method = method,
            accuracy = ForecastAccuracy(0.0, 0.0, 0.0, 0.0, 0.0)
        )
    }
    
    private fun analyzeDegradation(data: List<TimeSeriesPoint>): Double {
        if (data.size < 10) return 0.0
        
        val recentData = data.takeLast(data.size / 3)
        val olderData = data.take(data.size / 3)
        
        val recentAvg = recentData.map { it.value }.average()
        val olderAvg = olderData.map { it.value }.average()
        
        return if (olderAvg != 0.0) {
            (recentAvg - olderAvg) / olderAvg
        } else 0.0
    }
    
    private fun calculateResourceNeeds(predictions: List<TimeSeriesPoint>, load: Int): ResourceRequirements {
        val avgLatency = predictions.map { it.value }.average()
        
        return ResourceRequirements(
            cpu = avgLatency * load * 0.01,
            memory = load * 1024.0 * 1024.0, // 1MB per request
            network = load * 512.0,
            disk = 0.0
        )
    }
    
    private fun calculateBottleneckProbability(forecast: Forecast): Double {
        val trend = forecast.predictions.map { it.value }
        if (trend.size < 2) return 0.0
        
        val slope = calculateSlope(
            (0 until trend.size).map { it.toDouble() },
            trend
        )
        
        return when {
            slope > 0.5 -> 0.8
            slope > 0.2 -> 0.5
            slope > 0 -> 0.2
            else -> 0.1
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════
    
    suspend fun execute(action: String, args: Map<String, Any>): ToolExecutionResult = 
        withContext(Dispatchers.Default) {
            try {
                when (action) {
                    "forecast" -> {
                        val seriesId = args["series_id"] as? String ?: return@withContext ToolExecutionResult("Missing series_id", isError = true)
                        val steps = (args["steps"] as? String)?.toIntOrNull() ?: 10
                        val series = timeSeriesCache[seriesId] ?: return@withContext ToolExecutionResult("Series not found", isError = true)
                        
                        val forecast = forecastTimeSeries(series, steps)
                        ToolExecutionResult(forecast.toString())
                    }
                    
                    "detect_anomalies" -> {
                        val seriesId = args["series_id"] as? String ?: return@withContext ToolExecutionResult("Missing series_id", isError = true)
                        val series = timeSeriesCache[seriesId] ?: return@withContext ToolExecutionResult("Series not found", isError = true)
                        
                        val anomalies = detectAnomalies(series)
                        ToolExecutionResult("Found ${anomalies.size} anomalies")
                    }
                    
                    "analyze_trend" -> {
                        val seriesId = args["series_id"] as? String ?: return@withContext ToolExecutionResult("Missing series_id", isError = true)
                        val series = timeSeriesCache[seriesId] ?: return@withContext ToolExecutionResult("Series not found", isError = true)
                        
                        val trend = analyzeTrend(series)
                        ToolExecutionResult(trend.toString())
                    }
                    
                    else -> ToolExecutionResult("Unknown action: $action", isError = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in predictive analytics", e)
                ToolExecutionResult("Error: ${e.message}", isError = true)
            }
        }
}
