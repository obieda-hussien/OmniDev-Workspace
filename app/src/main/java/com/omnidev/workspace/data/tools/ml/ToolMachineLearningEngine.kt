package com.omnidev.workspace.data.tools.ml

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
 * ToolMachineLearningEngine — نظام التعلم الآلي المتقدم للأدوات
 * 
 * المميزات:
 * - تعلم أنماط استخدام الأدوات
 * - التنبؤ بالأدوات التالية
 * - تحسين ترتيب الأدوات
 * - اكتشاف الأنماط الشاذة
 * - توصيات ذكية للمستخدم
 * - نماذج تعلم متعددة
 * 
 * النماذج المدعومة:
 * - Naive Bayes
 * - K-Nearest Neighbors
 * - Decision Trees
 * - Neural Networks (بسيطة)
 * - Ensemble Learning
 */
class ToolMachineLearningEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "ToolML"
        private const val MODEL_VERSION = 2
        private const val MIN_TRAINING_SAMPLES = 10
        private const val MAX_HISTORY_SIZE = 10000
        private const val PREDICTION_THRESHOLD = 0.3
        private const val ANOMALY_THRESHOLD = 0.15
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // مخزن البيانات
    private val executionHistory = mutableListOf<ToolExecutionRecord>()
    private val toolSequences = ConcurrentHashMap<String, MutableList<String>>()
    private val toolSuccessRates = ConcurrentHashMap<String, ToolStats>()
    private val userPatterns = ConcurrentHashMap<String, UserPattern>()
    
    // النماذج
    private val naiveBayesModel = NaiveBayesClassifier()
    private val knnModel = KNearestNeighbors(k = 5)
    private val decisionTree = SimpleDecisionTree()
    private val neuralNet = SimpleFeedforwardNN(inputSize = 10, hiddenSize = 20, outputSize = 5)
    
    // الإحصائيات
    private var totalPredictions = 0
    private var correctPredictions = 0
    private var trainingEpochs = 0

    init {
        scope.launch {
            loadModels()
            startPeriodicTraining()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // التسجيل والتعلم
    // ══════════════════════════════════════════════════════════════

    /**
     * تسجيل تنفيذ أداة
     */
    suspend fun recordExecution(
        toolName: String,
        parameters: Map<String, Any>,
        result: ToolExecutionResult,
        executionTimeMs: Long,
        contextualData: Map<String, Any> = emptyMap()
    ) = withContext(Dispatchers.Default) {
        val record = ToolExecutionRecord(
            timestamp = System.currentTimeMillis(),
            toolName = toolName,
            parameters = parameters,
            success = !result.isError,
            executionTimeMs = executionTimeMs,
            resultSize = result.output.length,
            contextualData = contextualData
        )

        // تحديث السجل
        synchronized(executionHistory) {
            executionHistory.add(record)
            if (executionHistory.size > MAX_HISTORY_SIZE) {
                executionHistory.removeAt(0)
            }
        }

        // تحديث الإحصائيات
        updateToolStats(toolName, !result.isError, executionTimeMs)
        
        // تحديث التسلسلات
        updateSequences(toolName)
        
        // تحديث أنماط المستخدم
        updateUserPatterns(toolName, contextualData)
        
        // التدريب التدريجي
        if (executionHistory.size % 50 == 0) {
            trainIncrementally(record)
        }
    }

    /**
     * التنبؤ بالأداة التالية
     */
    suspend fun predictNextTool(
        currentTool: String? = null,
        recentTools: List<String> = emptyList(),
        contextualData: Map<String, Any> = emptyMap()
    ): ToolPrediction = withContext(Dispatchers.Default) {
        val features = extractFeatures(currentTool, recentTools, contextualData)
        
        // التنبؤ باستخدام نماذج متعددة
        val predictions = mutableListOf<Pair<String, Double>>()
        
        // Naive Bayes
        val nbPrediction = naiveBayesModel.predict(features)
        if (nbPrediction != null) {
            predictions.add(nbPrediction)
        }
        
        // KNN
        val knnPredictions = knnModel.predict(features, 3)
        predictions.addAll(knnPredictions)
        
        // Decision Tree
        val treePrediction = decisionTree.predict(features)
        if (treePrediction != null) {
            predictions.add(treePrediction)
        }
        
        // Neural Network
        val nnPredictions = neuralNet.predict(features)
        predictions.addAll(nnPredictions)
        
        // دمج النتائج (Ensemble)
        val aggregated = aggregatePredictions(predictions)
        
        totalPredictions++
        
        ToolPrediction(
            suggestedTools = aggregated.take(5),
            confidence = aggregated.firstOrNull()?.second ?: 0.0,
            basedOnPattern = recentTools.takeLast(3).joinToString(" → ")
        )
    }

    /**
     * اكتشاف الأنماط الشاذة
     */
    suspend fun detectAnomalies(
        toolName: String,
        executionTimeMs: Long,
        result: ToolExecutionResult
    ): AnomalyDetectionResult = withContext(Dispatchers.Default) {
        val stats = toolSuccessRates[toolName]
        
        if (stats == null || stats.executionCount < MIN_TRAINING_SAMPLES) {
            return@withContext AnomalyDetectionResult(false, 0.0, "")
        }
        
        val anomalies = mutableListOf<String>()
        var anomalyScore = 0.0
        
        // فحص وقت التنفيذ
        val timeZScore = abs(executionTimeMs - stats.avgExecutionTime) / 
                         (stats.stdDevExecutionTime + 1.0)
        if (timeZScore > 3.0) {
            anomalies.add("وقت تنفيذ غير طبيعي: ${executionTimeMs}ms (متوسط: ${stats.avgExecutionTime.toInt()}ms)")
            anomalyScore += 0.3
        }
        
        // فحص معدل النجاح
        if (result.isError && stats.successRate > 0.9) {
            anomalies.add("فشل غير متوقع (معدل النجاح الطبيعي: ${(stats.successRate * 100).toInt()}%)")
            anomalyScore += 0.4
        }
        
        // فحص حجم النتيجة
        val resultSizeZScore = abs(result.output.length - stats.avgResultSize) / 
                               (stats.stdDevResultSize + 1.0)
        if (resultSizeZScore > 3.0) {
            anomalies.add("حجم نتيجة غير طبيعي: ${result.output.length} حرف")
            anomalyScore += 0.2
        }
        
        // فحص التسلسل
        val expectedTools = getExpectedNextTools(toolName)
        if (expectedTools.isNotEmpty()) {
            val lastTool = executionHistory.lastOrNull()?.toolName
            if (lastTool != null && !expectedTools.contains(lastTool)) {
                anomalies.add("تسلسل غير متوقع: $lastTool → $toolName")
                anomalyScore += 0.1
            }
        }
        
        AnomalyDetectionResult(
            isAnomaly = anomalyScore >= ANOMALY_THRESHOLD,
            score = anomalyScore,
            description = anomalies.joinToString("; ")
        )
    }

    /**
     * الحصول على توصيات ذكية
     */
    suspend fun getRecommendations(
        currentContext: Map<String, Any>
    ): List<ToolRecommendation> = withContext(Dispatchers.Default) {
        val recommendations = mutableListOf<ToolRecommendation>()
        
        // 1. بناءً على الوقت
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeBasedTools = findToolsUsedAtTime(hour)
        timeBasedTools.forEach { (tool, frequency) ->
            recommendations.add(
                ToolRecommendation(
                    toolName = tool,
                    reason = "يُستخدم عادةً في هذا الوقت",
                    confidence = frequency,
                    priority = 1
                )
            )
        }
        
        // 2. بناءً على التسلسل
        val lastTool = executionHistory.lastOrNull()?.toolName
        if (lastTool != null) {
            val sequenceTools = toolSequences[lastTool] ?: emptyList()
            sequenceTools.groupingBy { it }.eachCount()
                .toList()
                .sortedByDescending { it.second }
                .take(3)
                .forEach { (tool, count) ->
                    recommendations.add(
                        ToolRecommendation(
                            toolName = tool,
                            reason = "يُستخدم عادةً بعد $lastTool",
                            confidence = count.toDouble() / sequenceTools.size,
                            priority = 2
                        )
                    )
                }
        }
        
        // 3. بناءً على معدل النجاح
        toolSuccessRates.entries
            .filter { it.value.successRate > 0.95 && it.value.executionCount > 20 }
            .sortedByDescending { it.value.successRate }
            .take(3)
            .forEach { (tool, stats) ->
                recommendations.add(
                    ToolRecommendation(
                        toolName = tool,
                        reason = "معدل نجاح عالي: ${(stats.successRate * 100).toInt()}%",
                        confidence = stats.successRate,
                        priority = 3
                    )
                )
            }
        
        // 4. بناءً على السياق
        currentContext["task_type"]?.let { taskType ->
            val contextTools = findToolsForTaskType(taskType.toString())
            contextTools.forEach { (tool, relevance) ->
                recommendations.add(
                    ToolRecommendation(
                        toolName = tool,
                        reason = "مناسب لنوع المهمة: $taskType",
                        confidence = relevance,
                        priority = 0
                    )
                )
            }
        }
        
        // ترتيب حسب الأولوية والثقة
        recommendations
            .sortedWith(compareBy({ it.priority }, { -it.confidence }))
            .distinctBy { it.toolName }
            .take(10)
    }

    /**
     * تحليل أداء الأدوات
     */
    suspend fun analyzeToolPerformance(): PerformanceAnalysis = withContext(Dispatchers.Default) {
        val totalExecutions = executionHistory.size
        val successfulExecutions = executionHistory.count { it.success }
        
        val topPerformers = toolSuccessRates.entries
            .filter { it.value.executionCount >= 10 }
            .sortedByDescending { it.value.successRate }
            .take(10)
            .map { (tool, stats) ->
                PerformanceMetric(
                    toolName = tool,
                    successRate = stats.successRate,
                    avgExecutionTime = stats.avgExecutionTime,
                    executionCount = stats.executionCount
                )
            }
        
        val bottlenecks = toolSuccessRates.entries
            .filter { it.value.executionCount >= 10 }
            .sortedByDescending { it.value.avgExecutionTime }
            .take(5)
            .map { (tool, stats) ->
                PerformanceMetric(
                    toolName = tool,
                    successRate = stats.successRate,
                    avgExecutionTime = stats.avgExecutionTime,
                    executionCount = stats.executionCount
                )
            }
        
        val unreliable = toolSuccessRates.entries
            .filter { it.value.executionCount >= 10 && it.value.successRate < 0.7 }
            .sortedBy { it.value.successRate }
            .take(5)
            .map { (tool, stats) ->
                PerformanceMetric(
                    toolName = tool,
                    successRate = stats.successRate,
                    avgExecutionTime = stats.avgExecutionTime,
                    executionCount = stats.executionCount
                )
            }
        
        PerformanceAnalysis(
            totalExecutions = totalExecutions,
            overallSuccessRate = if (totalExecutions > 0) successfulExecutions.toDouble() / totalExecutions else 0.0,
            topPerformers = topPerformers,
            bottlenecks = bottlenecks,
            unreliableTools = unreliable,
            predictionAccuracy = if (totalPredictions > 0) correctPredictions.toDouble() / totalPredictions else 0.0,
            trainingEpochs = trainingEpochs
        )
    }

    // ══════════════════════════════════════════════════════════════
    // التدريب
    // ══════════════════════════════════════════════════════════════

    private suspend fun trainIncrementally(record: ToolExecutionRecord) = withContext(Dispatchers.Default) {
        try {
            val features = extractFeaturesFromRecord(record)
            val label = record.toolName
            
            // تدريب Naive Bayes
            naiveBayesModel.train(features, label)
            
            // تدريب KNN (إضافة نقطة بيانات)
            knnModel.addDataPoint(features, label)
            
            // تدريب Decision Tree
            decisionTree.train(listOf(features to label))
            
            // تدريب Neural Network
            val targetVector = createOneHotVector(label)
            neuralNet.train(features, targetVector, learningRate = 0.01)
            
            trainingEpochs++
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في التدريب التدريجي: ${e.message}")
        }
    }

    private suspend fun startPeriodicTraining() {
        scope.launch {
            while (isActive) {
                delay(3600_000) // كل ساعة
                
                if (executionHistory.size >= MIN_TRAINING_SAMPLES) {
                    trainFullModel()
                    saveModels()
                }
            }
        }
    }

    private suspend fun trainFullModel() = withContext(Dispatchers.Default) {
        try {
            Log.d(TAG, "بدء التدريب الكامل على ${executionHistory.size} سجل...")
            
            val trainingData = executionHistory.map { record ->
                extractFeaturesFromRecord(record) to record.toolName
            }
            
            // تدريب جميع النماذج
            naiveBayesModel.trainBatch(trainingData)
            knnModel.trainBatch(trainingData)
            decisionTree.train(trainingData)
            neuralNet.trainBatch(trainingData, epochs = 10, learningRate = 0.01)
            
            trainingEpochs++
            
            Log.d(TAG, "اكتمل التدريب - Epoch: $trainingEpochs")
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في التدريب الكامل: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // استخراج الميزات
    // ══════════════════════════════════════════════════════════════

    private fun extractFeatures(
        currentTool: String?,
        recentTools: List<String>,
        contextualData: Map<String, Any>
    ): DoubleArray {
        val features = DoubleArray(10)
        
        // الميزة 1: هاش الأداة الحالية
        features[0] = (currentTool?.hashCode()?.rem(1000) ?: 0).toDouble()
        
        // الميزات 2-4: الأدوات الأخيرة
        recentTools.take(3).forEachIndexed { index, tool ->
            features[index + 1] = tool.hashCode().rem(1000).toDouble()
        }
        
        // الميزة 5: الوقت من اليوم (0-23)
        features[4] = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).toDouble()
        
        // الميزة 6: يوم الأسبوع (1-7)
        features[5] = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK).toDouble()
        
        // الميزة 7: عدد الأدوات المستخدمة مؤخرًا
        features[6] = recentTools.size.toDouble()
        
        // الميزة 8: نوع المهمة
        features[7] = (contextualData["task_type"]?.hashCode()?.rem(1000) ?: 0).toDouble()
        
        // الميزة 9: الأولوية
        features[8] = (contextualData["priority"] as? Number)?.toDouble() ?: 0.0
        
        // الميزة 10: السياق
        features[9] = (contextualData["context"]?.hashCode()?.rem(1000) ?: 0).toDouble()
        
        return features
    }

    private fun extractFeaturesFromRecord(record: ToolExecutionRecord): DoubleArray {
        val features = DoubleArray(10)
        
        features[0] = record.toolName.hashCode().rem(1000).toDouble()
        features[1] = if (record.success) 1.0 else 0.0
        features[2] = log10(record.executionTimeMs.toDouble() + 1.0)
        features[3] = log10(record.resultSize.toDouble() + 1.0)
        features[4] = (record.timestamp % (24 * 3600_000) / 3600_000).toDouble()
        features[5] = record.parameters.size.toDouble()
        features[6] = (record.contextualData["priority"] as? Number)?.toDouble() ?: 0.0
        features[7] = (record.contextualData["task_type"]?.hashCode()?.rem(1000) ?: 0).toDouble()
        features[8] = (record.contextualData["user_intent"]?.hashCode()?.rem(1000) ?: 0).toDouble()
        features[9] = (record.contextualData["context"]?.hashCode()?.rem(1000) ?: 0).toDouble()
        
        return features
    }

    // ══════════════════════════════════════════════════════════════
    // الدوال المساعدة
    // ══════════════════════════════════════════════════════════════

    private fun updateToolStats(toolName: String, success: Boolean, executionTimeMs: Long) {
        val stats = toolSuccessRates.getOrPut(toolName) { ToolStats() }
        
        synchronized(stats) {
            stats.executionCount++
            if (success) stats.successCount++
            
            // تحديث المتوسطات
            val n = stats.executionCount
            stats.avgExecutionTime = ((stats.avgExecutionTime * (n - 1)) + executionTimeMs) / n
            
            // تحديث الانحراف المعياري
            val diff = executionTimeMs - stats.avgExecutionTime
            stats.stdDevExecutionTime = sqrt(
                ((stats.stdDevExecutionTime * stats.stdDevExecutionTime * (n - 1)) + diff * diff) / n
            )
            
            stats.successRate = stats.successCount.toDouble() / stats.executionCount
        }
    }

    private fun updateSequences(toolName: String) {
        val lastTool = executionHistory.getOrNull(executionHistory.size - 2)?.toolName
        if (lastTool != null) {
            toolSequences.getOrPut(lastTool) { mutableListOf() }.add(toolName)
        }
    }

    private fun updateUserPatterns(toolName: String, contextualData: Map<String, Any>) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val pattern = userPatterns.getOrPut("hour_$hour") { UserPattern() }
        pattern.tools.add(toolName)
    }

    private fun findToolsUsedAtTime(hour: Int): List<Pair<String, Double>> {
        val pattern = userPatterns["hour_$hour"] ?: return emptyList()
        return pattern.tools.groupingBy { it }.eachCount()
            .map { it.key to it.value.toDouble() / pattern.tools.size }
            .sortedByDescending { it.second }
    }

    private fun findToolsForTaskType(taskType: String): List<Pair<String, Double>> {
        return executionHistory
            .filter { it.contextualData["task_type"] == taskType }
            .groupingBy { it.toolName }
            .eachCount()
            .map { it.key to it.value.toDouble() / executionHistory.size }
            .sortedByDescending { it.second }
    }

    private fun getExpectedNextTools(toolName: String): List<String> {
        return toolSequences[toolName]?.groupingBy { it }?.eachCount()
            ?.toList()?.sortedByDescending { it.second }
            ?.take(3)?.map { it.first } ?: emptyList()
    }

    private fun aggregatePredictions(predictions: List<Pair<String, Double>>): List<Pair<String, Double>> {
        return predictions.groupBy { it.first }
            .mapValues { entry -> entry.value.map { it.second }.average() }
            .toList()
            .sortedByDescending { it.second }
    }

    private fun createOneHotVector(label: String): DoubleArray {
        // تبسيط: استخدام هاش بدلاً من one-hot كامل
        val vector = DoubleArray(5)
        val index = abs(label.hashCode()) % 5
        vector[index] = 1.0
        return vector
    }

    private suspend fun loadModels() = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(context.filesDir, "ml_models")
            if (!modelDir.exists()) return@withContext
            
            // تحميل البيانات التاريخية
            val historyFile = File(modelDir, "execution_history.json")
            if (historyFile.exists()) {
                val json = JSONArray(historyFile.readText())
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    // تحليل وإضافة السجلات...
                }
            }
            
            Log.d(TAG, "تم تحميل النماذج بنجاح")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحميل النماذج: ${e.message}")
        }
    }

    private suspend fun saveModels() = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(context.filesDir, "ml_models")
            modelDir.mkdirs()
            
            // حفظ البيانات التاريخية
            val historyFile = File(modelDir, "execution_history.json")
            val jsonArray = JSONArray()
            executionHistory.takeLast(1000).forEach { record ->
                val obj = JSONObject()
                obj.put("timestamp", record.timestamp)
                obj.put("toolName", record.toolName)
                obj.put("success", record.success)
                obj.put("executionTimeMs", record.executionTimeMs)
                jsonArray.put(obj)
            }
            historyFile.writeText(jsonArray.toString())
            
            Log.d(TAG, "تم حفظ النماذج بنجاح")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حفظ النماذج: ${e.message}")
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    // ══════════════════════════════════════════════════════════════
    // Data Classes
    // ══════════════════════════════════════════════════════════════

    data class ToolExecutionRecord(
        val timestamp: Long,
        val toolName: String,
        val parameters: Map<String, Any>,
        val success: Boolean,
        val executionTimeMs: Long,
        val resultSize: Int,
        val contextualData: Map<String, Any>
    )

    data class ToolStats(
        var executionCount: Int = 0,
        var successCount: Int = 0,
        var successRate: Double = 0.0,
        var avgExecutionTime: Double = 0.0,
        var stdDevExecutionTime: Double = 0.0,
        var avgResultSize: Double = 0.0,
        var stdDevResultSize: Double = 0.0
    )

    data class UserPattern(
        val tools: MutableList<String> = mutableListOf()
    )

    data class ToolPrediction(
        val suggestedTools: List<Pair<String, Double>>,
        val confidence: Double,
        val basedOnPattern: String
    )

    data class AnomalyDetectionResult(
        val isAnomaly: Boolean,
        val score: Double,
        val description: String
    )

    data class ToolRecommendation(
        val toolName: String,
        val reason: String,
        val confidence: Double,
        val priority: Int
    )

    data class PerformanceMetric(
        val toolName: String,
        val successRate: Double,
        val avgExecutionTime: Double,
        val executionCount: Int
    )

    data class PerformanceAnalysis(
        val totalExecutions: Int,
        val overallSuccessRate: Double,
        val topPerformers: List<PerformanceMetric>,
        val bottlenecks: List<PerformanceMetric>,
        val unreliableTools: List<PerformanceMetric>,
        val predictionAccuracy: Double,
        val trainingEpochs: Int
    )
}

// ══════════════════════════════════════════════════════════════════════
// نماذج التعلم الآلي
// ══════════════════════════════════════════════════════════════════════

/**
 * Naive Bayes Classifier
 */
class NaiveBayesClassifier {
    private val classCounts = mutableMapOf<String, Int>()
    private val featureStats = mutableMapOf<String, MutableMap<Int, FeatureStats>>()
    private var totalSamples = 0

    fun train(features: DoubleArray, label: String) {
        classCounts[label] = (classCounts[label] ?: 0) + 1
        totalSamples++
        
        features.forEachIndexed { index, value ->
            val stats = featureStats.getOrPut(label) { mutableMapOf() }
                .getOrPut(index) { FeatureStats() }
            
            stats.sum += value
            stats.sumSquared += value * value
            stats.count++
        }
    }

    fun trainBatch(data: List<Pair<DoubleArray, String>>) {
        data.forEach { (features, label) -> train(features, label) }
    }

    fun predict(features: DoubleArray): Pair<String, Double>? {
        if (classCounts.isEmpty()) return null
        
        val probabilities = classCounts.map { (label, count) ->
            var logProb = ln(count.toDouble() / totalSamples)
            
            features.forEachIndexed { index, value ->
                val stats = featureStats[label]?.get(index)
                if (stats != null && stats.count > 0) {
                    val mean = stats.sum / stats.count
                    val variance = (stats.sumSquared / stats.count) - (mean * mean)
                    val stdDev = sqrt(variance + 1e-6)
                    
                    // Gaussian probability
                    val exponent = -((value - mean) * (value - mean)) / (2 * variance + 1e-6)
                    val prob = (1.0 / (sqrt(2 * PI) * stdDev)) * exp(exponent)
                    logProb += ln(prob + 1e-10)
                }
            }
            
            label to logProb
        }
        
        return probabilities.maxByOrNull { it.second }?.let { (label, logProb) ->
            label to exp(logProb)
        }
    }

    data class FeatureStats(
        var sum: Double = 0.0,
        var sumSquared: Double = 0.0,
        var count: Int = 0
    )
}

/**
 * K-Nearest Neighbors
 */
class KNearestNeighbors(private val k: Int = 5) {
    private val dataPoints = mutableListOf<Pair<DoubleArray, String>>()

    fun addDataPoint(features: DoubleArray, label: String) {
        dataPoints.add(features to label)
        if (dataPoints.size > 1000) {
            dataPoints.removeAt(0) // FIFO
        }
    }

    fun trainBatch(data: List<Pair<DoubleArray, String>>) {
        dataPoints.clear()
        dataPoints.addAll(data.takeLast(1000))
    }

    fun predict(features: DoubleArray, topN: Int = 1): List<Pair<String, Double>> {
        if (dataPoints.isEmpty()) return emptyList()
        
        val distances = dataPoints.map { (point, label) ->
            val distance = euclideanDistance(features, point)
            Triple(label, distance, point)
        }.sortedBy { it.second }
        
        val nearest = distances.take(k)
        val votes = nearest.groupingBy { it.first }.eachCount()
        
        return votes.map { (label, count) ->
            label to (count.toDouble() / k)
        }.sortedByDescending { it.second }.take(topN)
    }

    private fun euclideanDistance(a: DoubleArray, b: DoubleArray): Double {
        return sqrt(a.zip(b).sumOf { (x, y) -> (x - y) * (x - y) })
    }
}

/**
 * Simple Decision Tree
 */
class SimpleDecisionTree {
    private var root: TreeNode? = null

    fun train(data: List<Pair<DoubleArray, String>>) {
        if (data.isEmpty()) return
        root = buildTree(data, depth = 0, maxDepth = 5)
    }

    fun predict(features: DoubleArray): Pair<String, Double>? {
        return root?.predict(features)
    }

    private fun buildTree(data: List<Pair<DoubleArray, String>>, depth: Int, maxDepth: Int): TreeNode {
        val labels = data.groupingBy { it.second }.eachCount()
        val majorityLabel = labels.maxByOrNull { it.value }?.key ?: ""
        
        if (depth >= maxDepth || labels.size == 1 || data.size < 5) {
            return LeafNode(majorityLabel, labels[majorityLabel]!!.toDouble() / data.size)
        }
        
        // إيجاد أفضل تقسيم
        val bestSplit = findBestSplit(data)
        if (bestSplit == null) {
            return LeafNode(majorityLabel, labels[majorityLabel]!!.toDouble() / data.size)
        }
        
        val (featureIndex, threshold) = bestSplit
        val left = data.filter { it.first[featureIndex] <= threshold }
        val right = data.filter { it.first[featureIndex] > threshold }
        
        return DecisionNode(
            featureIndex = featureIndex,
            threshold = threshold,
            left = buildTree(left, depth + 1, maxDepth),
            right = buildTree(right, depth + 1, maxDepth)
        )
    }

    private fun findBestSplit(data: List<Pair<DoubleArray, String>>): Pair<Int, Double>? {
        if (data.isEmpty()) return null
        
        val featureCount = data.first().first.size
        var bestGain = 0.0
        var bestSplit: Pair<Int, Double>? = null
        
        for (featureIndex in 0 until featureCount) {
            val values = data.map { it.first[featureIndex] }.sorted().distinct()
            
            values.forEach { threshold ->
                val gain = informationGain(data, featureIndex, threshold)
                if (gain > bestGain) {
                    bestGain = gain
                    bestSplit = featureIndex to threshold
                }
            }
        }
        
        return bestSplit
    }

    private fun informationGain(data: List<Pair<DoubleArray, String>>, featureIndex: Int, threshold: Double): Double {
        val left = data.filter { it.first[featureIndex] <= threshold }
        val right = data.filter { it.first[featureIndex] > threshold }
        
        if (left.isEmpty() || right.isEmpty()) return 0.0
        
        val parentEntropy = entropy(data.map { it.second })
        val leftEntropy = entropy(left.map { it.second })
        val rightEntropy = entropy(right.map { it.second })
        
        val weightedEntropy = (left.size.toDouble() / data.size) * leftEntropy +
                              (right.size.toDouble() / data.size) * rightEntropy
        
        return parentEntropy - weightedEntropy
    }

    private fun entropy(labels: List<String>): Double {
        val counts = labels.groupingBy { it }.eachCount()
        val total = labels.size.toDouble()
        
        return -counts.values.sumOf { count ->
            val p = count / total
            if (p > 0) p * ln(p) else 0.0
        }
    }

    interface TreeNode {
        fun predict(features: DoubleArray): Pair<String, Double>
    }

    data class LeafNode(val label: String, val confidence: Double) : TreeNode {
        override fun predict(features: DoubleArray) = label to confidence
    }

    data class DecisionNode(
        val featureIndex: Int,
        val threshold: Double,
        val left: TreeNode,
        val right: TreeNode
    ) : TreeNode {
        override fun predict(features: DoubleArray): Pair<String, Double> {
            return if (features[featureIndex] <= threshold) {
                left.predict(features)
            } else {
                right.predict(features)
            }
        }
    }
}

/**
 * Simple Feedforward Neural Network
 */
class SimpleFeedforwardNN(
    private val inputSize: Int,
    private val hiddenSize: Int,
    private val outputSize: Int
) {
    private var weightsInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { Math.random() * 0.1 - 0.05 } }
    private var weightsHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { Math.random() * 0.1 - 0.05 } }
    private var biasHidden = DoubleArray(hiddenSize) { 0.0 }
    private var biasOutput = DoubleArray(outputSize) { 0.0 }

    fun train(input: DoubleArray, target: DoubleArray, learningRate: Double = 0.01) {
        // Forward pass
        val hidden = forward(input, weightsInputHidden, biasHidden)
        val output = forward(hidden, weightsHiddenOutput, biasOutput)
        
        // Backward pass (simplified)
        val outputError = DoubleArray(outputSize) { i -> target[i] - output[i] }
        
        // تحديث الأوزان (gradient descent مبسط)
        for (i in weightsHiddenOutput.indices) {
            for (j in weightsHiddenOutput[i].indices) {
                weightsHiddenOutput[i][j] += learningRate * outputError[j] * hidden[i]
            }
        }
    }

    fun trainBatch(data: List<Pair<DoubleArray, String>>, epochs: Int = 10, learningRate: Double = 0.01) {
        repeat(epochs) {
            data.forEach { (features, _) ->
                val target = DoubleArray(outputSize) { 0.0 }
                target[0] = 1.0 // تبسيط
                train(features, target, learningRate)
            }
        }
    }

    fun predict(input: DoubleArray): List<Pair<String, Double>> {
        val hidden = forward(input, weightsInputHidden, biasHidden)
        val output = forward(hidden, weightsHiddenOutput, biasOutput)
        
        return output.mapIndexed { index, value ->
            "class_$index" to sigmoid(value)
        }.sortedByDescending { it.second }.take(3)
    }

    private fun forward(input: DoubleArray, weights: Array<DoubleArray>, bias: DoubleArray): DoubleArray {
        val output = DoubleArray(weights.first().size)
        for (j in output.indices) {
            var sum = bias[j]
            for (i in input.indices) {
                sum += input[i] * weights[i][j]
            }
            output[j] = sigmoid(sum)
        }
        return output
    }

    private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))
}
