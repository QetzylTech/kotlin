// !DIAGNOSTICS: -UNUSED_VARIABLE

fun foo() {
    enum class A {
        FOO,
        BAR
    }
    val foo = A.FOO
    val b = object {
        enum class B {}
    }
    class C {
        <!NESTED_CLASS_NOT_ALLOWED!>enum class D<!> {}
    }
    val f = {
        enum class E {}
    }

    enum class<!SYNTAX!><!> {}
}
