package com.zsz.studyassistant.data

/** Classification hints frozen for this conversation; user instructions remain live settings. */
@kotlinx.serialization.Serializable
data class PromptInventory(val categories: List<String>, val tags: List<String>)
