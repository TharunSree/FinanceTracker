package com.example.financetracker.utils

object ValidationUtils {
    // Amount validation: Allows decimal numbers with optional negative sign
    private val AMOUNT_REGEX = """^-?\d+(\.\d{1,2})?$""".toRegex()

    // Date validation: YYYY-MM-DD format
    private val DATE_REGEX = """^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$""".toRegex()

    // Name validation: Allows letters, numbers, spaces, and common punctuation
    private val NAME_REGEX = """^[\w\s.,&'-]{1,100}$""".toRegex()

    // Category validation: Allows letters, numbers, and spaces
    private val CATEGORY_REGEX = """^[\w\s]{1,50}$""".toRegex()

    fun validateAmount(amount: String): Boolean =
        amount.matches(AMOUNT_REGEX) && amount.toDoubleOrNull() != null

    fun validateDate(date: String): Boolean = date.matches(DATE_REGEX)

    fun validateName(name: String): Boolean = name.matches(NAME_REGEX)

    fun validateCategory(category: String): Boolean = category.matches(CATEGORY_REGEX)
}