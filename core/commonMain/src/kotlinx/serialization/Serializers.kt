/*
 * Copyright 2017-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
@file:Suppress("DEPRECATION_ERROR", "UNCHECKED_CAST")
@file:JvmMultifileClass
@file:JvmName("SerializersKt")

package kotlinx.serialization

import kotlinx.serialization.builtins.*
import kotlinx.serialization.builtins.MapEntrySerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.TripleSerializer
import kotlinx.serialization.internal.*
import kotlinx.serialization.modules.*
import kotlin.jvm.*
import kotlin.native.concurrent.SharedImmutable
import kotlin.reflect.*

/**
 * Cache class for non-parametrized and non-contextual serializers.
 */
@SharedImmutable
private val SERIALIZERS_CACHE = createCache { it.serializerOrNull()?.cast() }

/**
 * Cache class for parametrized and non-contextual serializers.
 */
@SharedImmutable
private val PARAMETRIZED_SERIALIZERS_CACHE = createParametrizedCache { clazz, types ->
    val serializers = null.serializersForParameters(types, true)!!
    clazz.parametrizedSerializerOrNull(types, serializers)?.cast()
}

/**
 * Retrieves a serializer for the given type [T].
 * This method is a reified version of `serializer(KType)`.
 */
public inline fun <reified T> serializer(): KSerializer<T> {
    return serializer(typeOf<T>()).cast()
}

/**
 * Retrieves serializer for the given type [T] from the current [SerializersModule] and,
 * if not found, fallbacks to plain [serializer] method.
 */
public inline fun <reified T> SerializersModule.serializer(): KSerializer<T> {
    return serializer(typeOf<T>()).cast()
}

/**
 * Creates a serializer for the given [type].
 * [type] argument can be obtained with experimental [typeOf] method.
 *  @throws SerializationException if serializer cannot be created (provided [type] or its type argument is not serializable).
 */
@OptIn(ExperimentalSerializationApi::class)
public fun serializer(type: KType): KSerializer<Any?> = EmptySerializersModule().serializer(type)

/**
 * Creates a serializer for the given [type].
 * [type] argument can be obtained with experimental [typeOf] method.
 * Returns `null` if serializer cannot be created (provided [type] or its type argument is not serializable).
 */
@OptIn(ExperimentalSerializationApi::class)
public fun serializerOrNull(type: KType): KSerializer<Any?>? = EmptySerializersModule().serializerOrNull(type)

/**
 * Attempts to create a serializer for the given [type] and fallbacks to [contextual][SerializersModule.getContextual]
 * lookup for non-serializable types.
 * [type] argument can be obtained with experimental [typeOf] method.
 * @throws SerializationException if serializer cannot be created (provided [type] or its type argument is not serializable and is not registered in [this] module).
 */
@OptIn(ExperimentalSerializationApi::class)
public fun SerializersModule.serializer(type: KType): KSerializer<Any?> = serializerCommon(type)

/**
 * Attempts to create a serializer for the given [type] and fallbacks to [contextual][SerializersModule.getContextual]
 * lookup for non-serializable types.
 * [type] argument can be obtained with experimental [typeOf] method.
 * Returns `null` if serializer cannot be created (provided [type] or its type argument is not serializable and is not registered in [this] module).
 */
@OptIn(ExperimentalSerializationApi::class)
public fun SerializersModule.serializerOrNull(type: KType): KSerializer<Any?>? = serializerOrNullCommon(type)


private fun SerializersModule?.serializerCommon(type: KType): KSerializer<Any?> =
    serializerByKTypeImpl(type, failOnMissingTypeArgSerializer = true) ?: type.kclass()
        .platformSpecificSerializerNotRegistered()

private fun SerializersModule?.serializerOrNullCommon(type: KType): KSerializer<Any?>? {
    return serializerByKTypeImpl(type, failOnMissingTypeArgSerializer = false)
}

// the `this` is empty if there is no need to search for a contextual serializer
@OptIn(ExperimentalSerializationApi::class)
private fun SerializersModule?.serializerByKTypeImpl(
    type: KType,
    failOnMissingTypeArgSerializer: Boolean
): KSerializer<Any?>? {
    val rootClass = type.kclass()
    val isNullable = type.isMarkedNullable
    val typeArguments = type.arguments
        .map { requireNotNull(it.type) { "Star projections in type arguments are not allowed, but had $type" } }

    val cachedSerializer = if (typeArguments.isEmpty()) {
        SERIALIZERS_CACHE.get(rootClass, isNullable)
    } else {
        if (failOnMissingTypeArgSerializer) {
            PARAMETRIZED_SERIALIZERS_CACHE.get(rootClass, isNullable, typeArguments).getOrNull()
        } else {
            // return null if error occurred - serializer for parameter(s) was not found
            PARAMETRIZED_SERIALIZERS_CACHE.get(rootClass, isNullable, typeArguments).getOrElse { return null }
        }
    }
    cachedSerializer?.let { return it }

    // slow path to find contextual serializers in serializers module
    val contextualSerializer: KSerializer<out Any?>? = if (typeArguments.isEmpty()) {
        this?.getContextual(rootClass)
    } else {
        val serializers = serializersForParameters(typeArguments, failOnMissingTypeArgSerializer) ?: return null
        // first, we look among the built-in serializers, because the parameter could be contextual
        rootClass.parametrizedSerializerOrNull(typeArguments, serializers) ?: this?.getContextual(
            rootClass,
            serializers
        )
    }
    return contextualSerializer?.cast<Any>()?.nullable(isNullable)
}

/**
 * Returns null only if `failOnMissingTypeArgSerializer == false` and at least one parameter serializer not found.
 */
private fun SerializersModule?.serializersForParameters(
    typeArguments: List<KType>,
    failOnMissingTypeArgSerializer: Boolean
): List<KSerializer<Any?>>? {
    val serializers = if (failOnMissingTypeArgSerializer) {
        typeArguments.map { this.serializerCommon(it) }
    } else {
        typeArguments.map { this.serializerOrNullCommon(it) ?: return null }
    }
    return serializers
}




/**
 * Retrieves a [KSerializer] for the given [KClass].
 * The given class must be annotated with [Serializable] or be one of the built-in types.
 *
 * This method uses platform-specific reflection available for the given erased `KClass`
 * and is not recommended to use this method for anything, but last-ditch resort, e.g.
 * when all type info is lost, your application has crashed and it is the final attempt to log or send some serializable data.
 *
 * The recommended way to retrieve the serializer is inline [serializer] function and [`serializer(KType)`][serializer]
 *
 * This API is not guaranteed to work consistently across different platforms or
 * to work in cases that slightly differ from "plain @Serializable class" and have platform and reflection specific limitations.
 *
 * ### Constraints
 * This paragraph explains known (but not all!) constraints of the `serializer()` implementation.
 * Please note that they are not bugs, but implementation restrictions that we cannot workaround.
 *
 * * This method may behave differently on JVM, JS and Native because of runtime reflection differences
 * * Serializers for classes with generic parameters are ignored by this method
 * * External serializers generated with `Serializer(forClass = )` are not lookuped consistently
 * * Serializers for classes with named companion objects  are not lookuped consistently
 *
 * @throws SerializationException if serializer can't be found.
 */
@InternalSerializationApi
public fun <T : Any> KClass<T>.serializer(): KSerializer<T> = serializerOrNull() ?: serializerNotRegistered()

/**
 * Retrieves a [KSerializer] for the given [KClass] or returns `null` if none is found.
 * The given class must be annotated with [Serializable] or be one of the built-in types.
 * This method uses platform-specific reflection available for the given erased `KClass`
 * and it is not recommended to use this method for anything, but last-ditch resort, e.g.
 * when all type info is lost, your application has crashed and it is the final attempt to log or send some serializable data.
 *
 * This API is not guaranteed to work consistently across different platforms or
 * to work in cases that slightly differ from "plain @Serializable class".
 *
 * ### Constraints
 * This paragraph explains known (but not all!) constraints of the `serializerOrNull()` implementation.
 * Please note that they are not bugs, but implementation restrictions that we cannot workaround.
 *
 * * This method may behave differently on JVM, JS and Native because of runtime reflection differences
 * * Serializers for classes with generic parameters are ignored by this method
 * * External serializers generated with `Serializer(forClass = )` are not lookuped consistently
 * * Serializers for classes with named companion objects  are not lookuped consistently
 */
@InternalSerializationApi
public fun <T : Any> KClass<T>.serializerOrNull(): KSerializer<T>? =
    compiledSerializerImpl() ?: builtinSerializerOrNull()

private fun KClass<Any>.parametrizedSerializerOrNull(
    types: List<KType>,
    serializers: List<KSerializer<Any?>>
): KSerializer<out Any>? {
    // builtin first because some standard parametrized interfaces (e.g. Map) must use builtin serializer but not polymorphic
    return builtinParametrizedSerializer(types, serializers) ?: compiledParametrizedSerializer(serializers)
}


private fun KClass<Any>.compiledParametrizedSerializer(serializers: List<KSerializer<Any?>>): KSerializer<out Any>? {
    return constructSerializerForGivenTypeArgs(*serializers.toTypedArray())
}

@OptIn(ExperimentalSerializationApi::class)
private fun KClass<Any>.builtinParametrizedSerializer(
    typeArguments: List<KType>,
    serializers: List<KSerializer<Any?>>,
): KSerializer<out Any>? {
    // Array is not supported, see KT-32839
    return when (this) {
        Collection::class, List::class, MutableList::class, ArrayList::class -> ArrayListSerializer(serializers[0])
        HashSet::class -> HashSetSerializer(serializers[0])
        Set::class, MutableSet::class, LinkedHashSet::class -> LinkedHashSetSerializer(serializers[0])
        HashMap::class -> HashMapSerializer(serializers[0], serializers[1])
        Map::class, MutableMap::class, LinkedHashMap::class -> LinkedHashMapSerializer(
            serializers[0],
            serializers[1]
        )
        Map.Entry::class -> MapEntrySerializer(serializers[0], serializers[1])
        Pair::class -> PairSerializer(serializers[0], serializers[1])
        Triple::class -> TripleSerializer(serializers[0], serializers[1], serializers[2])
        else -> {
            if (isReferenceArray(this)) {
                return ArraySerializer(typeArguments[0].classifier as KClass<Any>, serializers[0])
            } else {
                return null
            }
        }
    }
}

internal fun <T : Any> KSerializer<T>.nullable(shouldBeNullable: Boolean): KSerializer<T?> {
    if (shouldBeNullable) return nullable
    return this as KSerializer<T?>
}
