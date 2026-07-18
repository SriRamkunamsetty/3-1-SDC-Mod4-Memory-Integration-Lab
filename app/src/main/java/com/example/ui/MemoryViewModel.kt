package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.*
import com.example.data.*
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

@JsonClass(generateAdapter = true)
data class ExtractedFact(
    val fact: String,
    val category: String? = "General"
)

enum class PipelineStage {
    IDLE,
    RETRIEVING_MEMORIES,
    SYNTHESIZING_PROMPT,
    CALLING_GEMINI,
    EXTRACTING_FACTS,
    COMPLETED,
    ERROR
}

data class PipelineStepLog(
    val stage: PipelineStage,
    val timestamp: Long = System.currentTimeMillis(),
    val description: String,
    val isSuccess: Boolean = true
)

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val currentStage: PipelineStage = PipelineStage.IDLE,
    val pipelineLogs: List<PipelineStepLog> = emptyList(),
    val isApiKeyConfigured: Boolean = false,
    val apiLatencyMs: Long = 0,
    val lastExtractedFact: String? = null
)

class MemoryViewModel(
    application: Application,
    private val repository: MemoryRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // Check if API key is configured and not the default placeholder
        val apiKey = BuildConfig.GEMINI_API_KEY
        val isKeyValid = apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY"
        
        _uiState.update { it.copy(isApiKeyConfigured = isKeyValid) }

        // Observe local databases
        viewModelScope.launch {
            combine(
                repository.allMemoriesFlow,
                repository.allMessagesFlow
            ) { memories, messages ->
                _uiState.update {
                    it.copy(
                        memories = memories,
                        messages = messages
                    )
                }
            }.collect()
        }

        // Pre-populate with a warm initial memory (using additional metadata user email)
        viewModelScope.launch {
            val existing = repository.getAllMemories()
            if (existing.isEmpty()) {
                repository.insertMemory(
                    Memory(
                        content = "User email is mohansriramkunamsetty@gmail.com",
                        category = "Personal",
                        isManual = false,
                        confidence = 1.0
                    )
                )
                repository.insertMemory(
                    Memory(
                        content = "I am a developer participating in the Memory Integration Lab.",
                        category = "Work",
                        isManual = false,
                        confidence = 0.95
                    )
                )
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = ChatMessage(role = "user", text = text)
        
        viewModelScope.launch {
            // 1. Insert user message to local history
            repository.insertMessage(userMessage)
            
            val logs = mutableListOf<PipelineStepLog>()
            _uiState.update { 
                it.copy(
                    currentStage = PipelineStage.RETRIEVING_MEMORIES,
                    pipelineLogs = emptyList(),
                    lastExtractedFact = null
                ) 
            }

            // Step 1: Retrieval
            logs.add(PipelineStepLog(PipelineStage.RETRIEVING_MEMORIES, description = "Scanning local database for existing user context..."))
            _uiState.update { it.copy(pipelineLogs = logs.toList()) }
            
            val memories = repository.getAllMemories()
            // Find relevant memories (heuristics: term match or direct fetch all for small dataset RAG)
            val relevantMemories = memories.filter { memory ->
                val keywords = text.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }
                keywords.any { memory.content.lowercase().contains(it) } || memory.category.lowercase() == "personal"
            }
            
            val retrievedInfo = if (relevantMemories.isNotEmpty()) {
                "Retrieved ${relevantMemories.size} relevant memories based on semantic keyword match."
            } else {
                "No strong context keyword matched. Using top personal context memories (total ${memories.size} cached)."
            }
            logs.add(PipelineStepLog(PipelineStage.RETRIEVING_MEMORIES, description = "$retrievedInfo\n" + 
                memories.joinToString("\n") { "- [${it.category}] ${it.content}" }))
            _uiState.update { it.copy(pipelineLogs = logs.toList()) }

            // Step 2: Prompt Synthesis
            _uiState.update { it.copy(currentStage = PipelineStage.SYNTHESIZING_PROMPT) }
            logs.add(PipelineStepLog(PipelineStage.SYNTHESIZING_PROMPT, description = "Synthesizing specialized System Instructions with injected memory context."))
            
            val systemInstructionText = """
                You are a warm, highly intuitive memory-enabled mobile assistant. 
                Your primary goal is to help the user while subtly showing that you remember previous facts they shared.
                
                Below are the persistent personal memories retrieved about the user from the secure device database. 
                Use these facts naturally to inform your answers, but do NOT awkwardly regurgitate them if not relevant.
                
                USER MEMORIES:
                ${if (memories.isNotEmpty()) memories.joinToString("\n") { "- Category: ${it.category} | Detail: ${it.content}" } else "No memories recorded yet."}
                
                GUIDELINES:
                1. If the user asks about themselves, use these memories to reply.
                2. Be warm, empathetic, and professional.
                3. Do not invent details not present in the memories. If you don't know, just ask!
            """.trimIndent()
            
            logs.add(PipelineStepLog(PipelineStage.SYNTHESIZING_PROMPT, description = "Prompt Synthesis complete. System Instruction character count: ${systemInstructionText.length}."))
            _uiState.update { it.copy(pipelineLogs = logs.toList()) }

            // Step 3: Calling Gemini
            _uiState.update { it.copy(currentStage = PipelineStage.CALLING_GEMINI) }
            logs.add(PipelineStepLog(PipelineStage.CALLING_GEMINI, description = "Executing inference using model 'gemini-3.5-flash'..."))
            _uiState.update { it.copy(pipelineLogs = logs.toList()) }

            val startTime = System.currentTimeMillis()
            var responseText = ""
            var success = false

            try {
                val chatMessages = repository.allMessagesFlow.first().takeLast(10) // Grab last 10 messages for context
                val apiContents = chatMessages.map { msg ->
                    Content(
                        role = if (msg.role == "user") "user" else "model",
                        parts = listOf(Part(text = msg.text))
                    )
                }

                val request = GenerateContentRequest(
                    contents = apiContents,
                    systemInstruction = Content(parts = listOf(Part(text = systemInstructionText)))
                )

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.service.generateContent(BuildConfig.GEMINI_API_KEY, request)
                }

                responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                    ?: "I'm sorry, I was unable to generate a response. Please check your connectivity and try again."
                success = true
            } catch (e: Exception) {
                responseText = "Error generating response: ${e.localizedMessage}"
                success = false
            }

            val latency = System.currentTimeMillis() - startTime
            _uiState.update { it.copy(apiLatencyMs = latency) }

            if (success) {
                logs.add(PipelineStepLog(PipelineStage.CALLING_GEMINI, description = "Gemini execution successful in ${latency}ms."))
            } else {
                logs.add(PipelineStepLog(PipelineStage.CALLING_GEMINI, description = "Gemini execution failed: $responseText", isSuccess = false))
                _uiState.update { it.copy(currentStage = PipelineStage.ERROR) }
            }
            _uiState.update { it.copy(pipelineLogs = logs.toList()) }

            // Step 4: Autonomous Memory Extraction
            if (success) {
                _uiState.update { it.copy(currentStage = PipelineStage.EXTRACTING_FACTS) }
                logs.add(PipelineStepLog(PipelineStage.EXTRACTING_FACTS, description = "Triggering autonomous background extractor pipeline to detect new persistent facts..."))
                _uiState.update { it.copy(pipelineLogs = logs.toList()) }

                val extractionSystemInstruction = """
                    You are an advanced background extraction agent in a Memory-Enabled AI pipeline.
                    Your sole task is to analyze the user's latest statement and extract any new, high-value, persistent details about the user (e.g., their name, age, email, job, hobby, favorite color, pet, family detail, relationship, likes, dislikes, preferences).
                    
                    We already know these facts about them:
                    ${memories.joinToString("\n") { "- ${it.content}" }}
                    
                    RULES:
                    1. If the message contains a NEW persistent fact or preference that is NOT already known, extract it.
                    2. Write the fact clearly and concisely in the FIRST person (e.g., "I love green color", "I have a cat named Oscar").
                    3. Categorize it as: "Personal", "Preference", "Fact", "Work", or "General".
                    4. If the message is a greeting, basic question, random comment, or does NOT contain any long-term persistent details, you MUST reply with {"fact": "None"}.
                    5. Respond ONLY in valid JSON matching this schema: {"fact": "Fact description", "category": "Category"}
                """.trimIndent()

                try {
                    val extractionRequest = GenerateContentRequest(
                        contents = listOf(
                            Content(role = "user", parts = listOf(Part(text = "User message: \"$text\"")))
                        ),
                        systemInstruction = Content(parts = listOf(Part(text = extractionSystemInstruction))),
                        generationConfig = GenerationConfig(responseMimeType = "application/json", temperature = 0.2f)
                    )

                    val extractResponse = withContext(Dispatchers.IO) {
                        RetrofitClient.service.generateContent(BuildConfig.GEMINI_API_KEY, extractionRequest)
                    }

                    val extractText = extractResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    if (extractText != null) {
                        val adapter = RetrofitClient.moshi.adapter(ExtractedFact::class.java)
                        val result = adapter.fromJson(extractText)
                        if (result != null && result.fact != "None" && result.fact.isNotBlank()) {
                            // Check for redundancy
                            val isRedundant = memories.any { it.content.lowercase() == result.fact.lowercase() }
                            if (!isRedundant) {
                                val category = result.category ?: "General"
                                val newMemory = Memory(
                                    content = result.fact,
                                    category = category,
                                    isManual = false,
                                    confidence = 0.9
                                )
                                repository.insertMemory(newMemory)
                                logs.add(PipelineStepLog(PipelineStage.EXTRACTING_FACTS, description = "LEARNED NEW FACT: Found detail to persist!\n- Saved: \"${result.fact}\" in category [${category}]."))
                                _uiState.update { it.copy(lastExtractedFact = result.fact) }
                            } else {
                                logs.add(PipelineStepLog(PipelineStage.EXTRACTING_FACTS, description = "No new details found. Fact \"${result.fact}\" is already stored in database."))
                            }
                        } else {
                            logs.add(PipelineStepLog(PipelineStage.EXTRACTING_FACTS, description = "Analysis complete. Message contains no long-term persistent details to remember."))
                        }
                    }
                } catch (e: Exception) {
                    logs.add(PipelineStepLog(PipelineStage.EXTRACTING_FACTS, description = "Background extractor skipped or failed: ${e.localizedMessage}", isSuccess = false))
                }
            }

            // Step 5: Completed
            if (success) {
                _uiState.update { it.copy(currentStage = PipelineStage.COMPLETED) }
                logs.add(PipelineStepLog(PipelineStage.COMPLETED, description = "Pipeline execution fully optimized and completed."))
            }
            _uiState.update { it.copy(pipelineLogs = logs.toList()) }

            // Insert AI response with the generated logs
            val formattedLogs = StringBuilder()
            logs.forEach { log ->
                val emoji = when (log.stage) {
                    PipelineStage.RETRIEVING_MEMORIES -> "🔍"
                    PipelineStage.SYNTHESIZING_PROMPT -> "⚙️"
                    PipelineStage.CALLING_GEMINI -> "🤖"
                    PipelineStage.EXTRACTING_FACTS -> "🧠"
                    PipelineStage.COMPLETED -> "✅"
                    PipelineStage.ERROR -> "❌"
                    else -> "⏳"
                }
                formattedLogs.append("$emoji **${log.stage.name}**\n${log.description}\n\n")
            }

            val assistantMsg = ChatMessage(
                role = "assistant",
                text = responseText,
                pipelineLogs = formattedLogs.toString()
            )
            repository.insertMessage(assistantMsg)
            _uiState.update { it.copy(currentStage = PipelineStage.IDLE) }
        }
    }

    fun addManualMemory(content: String, category: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.insertMemory(
                Memory(
                    content = content,
                    category = category,
                    isManual = true,
                    confidence = 1.0
                )
            )
        }
    }

    fun deleteMemory(id: Int) {
        viewModelScope.launch {
            repository.deleteMemoryById(id)
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            repository.clearAllMemories()
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            repository.clearChatHistory()
        }
    }
}

class MemoryViewModelFactory(
    private val application: Application,
    private val repository: MemoryRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MemoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MemoryViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
