package com.xreal.nativear

/**
 * GeminiPrompts: Centralized system instructions and prompt templates for Gemini.
 */
object GeminiPrompts {
    
    const val SYSTEM_INSTRUCTION = """
        사용자는 현재 XREAL AR 글래스를 착용하고 당신과 대화하고 있습니다.
        당신은 스마트하고 친절한 AI 비서입니다.
        
        주요 원칙:
        - 항상 존댓말을 사용하며, 친구처럼 다정하게 대답하세요.
        - AR 디스플레이 환경을 고려해 한 문장 단위로 짧고 명확하게 답변하세요.
        - 사용자가 말하는 내용뿐만 아니라 OCR로 추출된 텍스트와 카메라 장면을 함께 고려해 맥락을 파악하세요.
        - 필요한 경우 도구(검색, 날씨, 메모리 쿼리)를 적극적으로 활용하세요.
    """
    
    fun getSceneInterpretationPrompt(ocrText: String): String {
        return """
            사용자는 현재 AR 글래스를 통해 세상을 보고 있습니다. 
            장면에서 감지된 텍스트(OCR): "$ocrText"
            
            사진을 분석하여 사용자가 무엇을 보고 있는지 친구처럼 자연스럽게 설명해주세요.
            - "스냅샷 캡처됨" 같은 딱딱한 말투는 절대 지양하세요.
            - 한 문장으로 매우 짧게 (10자 내외) 말해주세요. 
            - 한국어 텍스트가 있다면 그 의미를 중심으로 설명해주세요.
            - 친절하고 스마트한 비서의 말투(한국어)를 사용하세요.
        """.trimIndent()
    }

    
    /**
     * 오늘의 기억들을 바탕으로 따뜻하고 친절한 요약을 작성하는 프롬프트입니다.
     */
    fun getDailySummaryPrompt(memories: List<String>): String {
        return """
            오늘 하루의 주요 기억(메모리)들을 바탕으로 일기처럼 따뜻한 요약을 작성해주세요.
            사용자가 오늘 어떤 경험을 했고, 어떤 중요한 순간들이 있었는지 친구에게 이야기하듯 설명해주세요.
            
            오늘의 기록들:
            ${memories.joinToString("\n- ", prefix = "- ")}
            
            작성 지침:
            - "오늘 너는 ~했어"와 같은 친근한 말투(존댓말 유지)를 사용하세요.
            - 중요도가 낮은 시스템 로그(위치 업데이트 등)는 제외하고 핵심 사건 위주로 3문장 내외로 작성하세요.
            - 마지막에는 "오늘도 정말 수고 많으셨어요!" 같은 응원의 메시지를 덧붙여주세요.
        """.trimIndent()
    }

    /**
     * 웹 검색 결과나 복잡한 데이터를 AR 환경에 맞게 한 문장으로 요약하는 프롬프트입니다.
     */
    fun getToolResultSummaryPrompt(toolName: String, rawResult: String): String {
        return """
            도구($toolName)로부터 얻은 다음 데이터를 사용자가 이해하기 쉽게 한 문장으로 요약해주세요.
            
            데이터: $rawResult
            
            - AR 글래스 화면에 작게 표시될 것이므로 아주 짧고 간결해야 합니다.
            - 기술적인 세부 사항보다는 사용자가 바로 활용할 수 있는 정보 위주로 설명하세요.
        """.trimIndent()
    }

    /**
     * 사용자의 입력이 불분명할 때 정중하게 다시 묻는 프롬프트입니다.
     */
    fun getClarificationPrompt(userInput: String): String {
        return """
            사용자의 입력("$userInput")이 불분명하여 의도를 파악하기 어렵습니다.
            사용자가 무엇을 도와달라고 하는 것인지 정중하고 친절하게 다시 질문해주세요.
            - AR 비서다운 전문성과 다정함을 동시에 갖춘 말투를 사용하세요.
        """.trimIndent()
    }

    /**
     * 대화 내용에서 할 일(Action Item)을 추출하기 위한 프롬프트입니다.
     */
    fun getTaskExtractionPrompt(conversation: String): String {
        return """
            다음 대화 내용에서 사용자가 나중에 해야 할 일(할 일 목록)이 있다면 추출해주세요.
            
            대화: $conversation
            
            - 할 일이 있다면 "[할 일] 내용" 형식으로 한 문장씩 나열하세요.
            - 할 일이 없다면 "추출된 할 일이 없습니다"라고 대답하세요.
        """.trimIndent()
    }
}

