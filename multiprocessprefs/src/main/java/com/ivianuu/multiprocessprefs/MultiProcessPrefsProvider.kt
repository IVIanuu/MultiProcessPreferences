/*
 * Copyright 2018 Manuel Wrage
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ivianuu.multiprocessprefs

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.MatrixCursor
import android.net.Uri
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MultiProcessPrefsProvider : ContentProvider(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val preferences =
        mutableMapOf<String, SharedPreferences>()

    private val contentUri by lazy { Uri.parse("content://${context!!.packageName}.prefs") }

    private val pendingChanges = mutableMapOf<String, Any>()

    private val lock = ReentrantLock()

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ) = lock.withLock {
        val map = getSharedPrefs(uri).all

        MatrixCursor(PROJECTION).apply {
            map.forEach { (key, value) ->
                newRow()
                    .add(key)
                    .add(value!!.prefType.toString())
                    .add(value.serialize())
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues): Uri? {
        throw IllegalArgumentException("unsupported operation use update instead")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw IllegalArgumentException("unsupported operation use update instead")
    }

    override fun update(
        uri: Uri,
        values: ContentValues,
        selection: String?,
        selectionArgs: Array<String>?
    ) = lock.withLock {
        val action = Action.valueOf(values.getAsString(KEY_ACTION))
        val name = uri.pathSegments[0]
        val changeId = values.getAsString(KEY_CHANGE_ID)

        val sharedPrefs = getSharedPrefs(uri)

        when (action) {
            Action.PUT -> {
                val key = values.getAsString(KEY_KEY)
                val prefType = PrefType.valueOf(values.getAsString(KEY_TYPE))
                val value = values.getAsString(KEY_VALUE).deserialize(prefType)

                pendingChanges[key] = value
                sharedPrefs.edit().putAny(key, value).apply()

                context!!.contentResolver.notifyChange(
                    getChangeUri(key, name, changeId), null
                )
            }
            Action.REMOVE -> {
                val key = values.getAsString(KEY_KEY)

                if (sharedPrefs.contains(key)) {
                    pendingChanges[key] = this
                    sharedPrefs.edit().remove(key).apply()
                }

                // should we dispatch this always? // todo
                context!!.contentResolver.notifyChange(
                    getChangeUri(key, name, changeId), null
                )
            }
            Action.CLEAR -> {
                sharedPrefs.edit().clear().apply()
            }
        }

        0
    }

    override fun getType(uri: Uri): String? = null

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        lock.withLock {
            val pendingChange = pendingChanges.remove(key)

            val newValue = sharedPreferences.all[key]

            // "this" indicates that the value where removed
            if ((pendingChange == newValue) || (pendingChange == this && newValue == null)) return

            val name = preferences.toList()
                .first { it.second == sharedPreferences }
                .first

            val uri = getChangeUri(key, name, UUID.randomUUID().toString())
            context!!.contentResolver.notifyChange(uri, null)
        }
    }

    private fun getChangeUri(key: String, name: String, changeId: String): Uri =
        contentUri.buildUpon()
            .appendPath(name)
            .appendPath(key)
            .appendPath(changeId)
            .build()

    private fun getSharedPrefs(uri: Uri) = lock.withLock {
        val name = uri.pathSegments[0]
        preferences.getOrPut(name) {
            context!!.getSharedPreferences(name, Context.MODE_PRIVATE).apply {
                registerOnSharedPreferenceChangeListener(this@MultiProcessPrefsProvider)
            }
        }
    }

}