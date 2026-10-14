/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import android.content.Context
import io.element.encrypteddb.SqlCipherDriverFactory
import io.element.encrypteddb.passphrase.RandomDatabaseSecretProvider

/**
 * The one local memo database of the app, `june_memo.db`, encrypted with SQLCipher like the push database (설계 §a).
 * The key is a random secret kept in an encrypted file next to the database. Opening reads the disk: call it off the
 * main thread.
 */
internal object MemoDatabase {
    private const val NAME = "june_memo"

    @Volatile
    private var db: MemoDb? = null

    fun get(context: Context): MemoDb = db ?: synchronized(this) {
        db ?: open(context.applicationContext).also { db = it }
    }

    private fun open(context: Context): MemoDb {
        val secretFile = context.getDatabasePath("$NAME.key")
        secretFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val driver = SqlCipherDriverFactory(RandomDatabaseSecretProvider(context, secretFile))
            .create(schema = MemoDb.Schema, name = "$NAME.db", context = context)
        return MemoDb(driver)
    }
}
