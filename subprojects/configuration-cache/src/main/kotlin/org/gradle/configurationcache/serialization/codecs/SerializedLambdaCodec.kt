/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.configurationcache.serialization.codecs

import org.gradle.configurationcache.serialization.Codec
import org.gradle.configurationcache.serialization.ReadContext
import org.gradle.configurationcache.serialization.WriteContext
import org.gradle.configurationcache.serialization.beans.unsupportedFieldDeclaredTypes
import org.gradle.configurationcache.serialization.logUnsupported
import org.gradle.configurationcache.serialization.readNonNull
import org.objectweb.asm.Type
import org.objectweb.asm.Type.getArgumentTypes
import java.lang.invoke.SerializedLambda
import kotlin.reflect.KClass

object SerializedLambdaCodec : Codec<SerializedLambda> {
    override suspend fun WriteContext.encode(value: SerializedLambda) {
        doTheChecks(value)
        write(BeanSpec(value))
    }

    private
    fun WriteContext.doTheChecks(value: SerializedLambda) {
        val signature = value.implMethodSignature
        val paramTypes: Array<Type> = getArgumentTypes(signature)
        paramTypes.forEach { paramType ->
            unsupportedTypes[paramType]?.let { unsupportedKClass ->
                logUnsupported("serialize", unsupportedKClass)
            }
        }
    }

    override suspend fun ReadContext.decode(): SerializedLambda {
        return readNonNull<BeanSpec>().bean as SerializedLambda
    }

    private val unsupportedTypes: Map<Type, KClass<*>> =
        unsupportedFieldDeclaredTypes.associate { Type.getType(it.java) to it }
}
