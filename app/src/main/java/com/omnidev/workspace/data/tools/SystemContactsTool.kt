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
 * invoked with a real number.
 *
 * **Safety contract:** Requires [Manifest.permission.READ_CONTACTS].  If that
 * permission has not been granted the tool returns a graceful error message instead
 * of throwing.
 */
object SystemContactsTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "search_contacts",
            description = "CRITICAL: Use this tool IMMEDIATELY when the user asks to find a " +
                "person's phone number or search contacts. DO NOT use codebase search or terminal " +
                "commands for this. Queries the device's system contacts and returns all matching " +
                "names and phone numbers for the given search term.",
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
            // Guard: permission check
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext ToolExecutionResult(
                    output = "Permission required: READ_CONTACTS has not been granted. " +
                        "Please grant the Contacts permission to this app in Settings and try again.",
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

            val results = mutableListOf<String>()

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
                val nameCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val number = c.getString(numberCol) ?: continue
                    results.add("Name: $name, Phone: $number")
                }
            }

            if (results.isEmpty()) {
                ToolExecutionResult(output = "No contacts found matching \"$searchName\" in device system contacts.")
            } else {
                ToolExecutionResult(output = results.joinToString("\n"))
            }
        }
}
