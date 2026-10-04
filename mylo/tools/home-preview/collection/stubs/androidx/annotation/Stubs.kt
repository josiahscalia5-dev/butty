// Harness-only stand-ins: annotations used by androidx.collection.
package androidx.annotation

@Retention(AnnotationRetention.BINARY)
annotation class IntRange(val from: Long = Long.MIN_VALUE, val to: Long = Long.MAX_VALUE)

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class RestrictTo(vararg val value: Scope) {
    enum class Scope { LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, GROUP_ID, TESTS, SUBCLASSES }
}
