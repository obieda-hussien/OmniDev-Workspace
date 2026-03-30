package com.omnidev.workspace.data.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Queries the device's system contacts using [ContactsContract].
 *
 * Use this tool BEFORE calling [CommunicationTool] when you only have a person's
 * name — it returns the matching phone numbers so that `communicate_tool` can be
 * invoked with a clean, dialable number.
 *
 * **Safety contract:** Requires [Manifest.permission.READ_CONTACTS]. If that
 * permission has not been granted, the tool returns a graceful error message instead
 * of throwing an exception.
 */
object SystemContactsTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "search_contacts",
            description = "CRITICAL: Use this tool IMMEDIATELY when the user asks to find a " +
                "person's phone number or search contacts. DO NOT use codebase search or terminal " +
                "commands for this. Queries the device's system contacts and returns all matching " +
                "names and cleanly formatted phone numbers for the given search term.",
            parameters = listOf(
                ToolParameter(
                    name = "searchName",
                    type = "string",
                    description = "Full or partial name of the contact to search for (case-insensitive).",
                    required = true
                )
            )
        )
    )

    suspend fun execute(context: Context, searchName: String): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            // Guard: Permission check
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext ToolExecutionResult(
                    output = "Permission required: READ_CONTACTS has not been granted. " +
                        "Please ask the user to grant the Contacts permission in Settings.",
                    isError = true
                )
            }

            if (searchName.isBlank()) {
                return@withContext ToolExecutionResult(
                    output = "Missing required argument: searchName must not be empty.",
                    isError = true
                )
            }

            // Escape SQL LIKE special characters so the search term is treated as
            // a literal substring (% and _ would otherwise act as wildcards).
            val escapedName = searchName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

            // Data class to hold and normalize contact data
            data class ContactEntry(val name: String, val rawNumber: String) {
                // Strip everything except plus signs and digits for Intent compatibility
                val cleanNumber: String = rawNumber.replace(Regex("[^+\\d]"), "")
            }

            val rawContacts = mutableSetOf<ContactEntry>()

            // Query the Contacts Provider
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                arrayOf("%$escapedName%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use { c ->
                val nameCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                // Safety check for valid columns
                if (nameCol == -1 || numberCol == -1) return@use

                while (c.moveToNext()) {
                    val name = c.getString(nameCol)?.trim() ?: continue
                    val number = c.getString(numberCol)?.trim() ?: continue
                    if (number.isNotBlank()) {
                        rawContacts.add(ContactEntry(name, number))
                    }
                }
            }

            if (rawContacts.isEmpty()) {
                return@withContext ToolExecutionResult(
                    output = "❌ No contacts found matching \"$searchName\" in the device's system contacts."
                )
            }

            // Deduplicate and group by name to handle linked accounts (WhatsApp, Google, etc.)
            // returning the exact same name with the exact same phone number multiple times.
            val groupedContacts = rawContacts.groupBy { it.name }
            
            val formattedOutput = buildString {
                appendLine("Found ${groupedContacts.size} matching contact(s):")
                groupedContacts.forEach { (name, entries) ->
                    appendLine("- **$name**")
                    // Distinct by clean number to remove duplicate sync-account entries
                    entries.distinctBy { it.cleanNumber }.forEach { entry ->
                        appendLine("  📞 `${entry.cleanNumber}` (Raw: ${entry.rawNumber})")
                    }
                }
            }.trimEnd()

            ToolExecutionResult(output = formattedOutput)
        }
}
