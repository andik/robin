Templates allow extensive reuse of C++ code, and are used widely in C++ applications and libraries. Generally, a class template `C<T>` is declared, and concrete classes `C<int>`, `C<char>`, `C<std::string>` are so-called instantiated by the compiler to support various uses of the template across the program.

When creating a binding with Robin, one has to make a decision concerning which template instances will be generated. A template instance may be requested either explicitly or implicitly:

  * Explicit. To request a specific instance, declare a C++ alias (typedef) in your code and provide the aliased name when invoking Griffin (or - if no class names are specified - Griffin will collect the alias on its own).
  * Implicit. You may also specify the template name, without angle brackets, which will cause Griffin to scan the given interfaces for any occurrence of this template, and collect any instances encountered. Note that only types appearing in function and method prototypes will be considered, not those inside the actual definitions.

**Example.**

> ab.h

```
template <typename T>
class A { };

class B {
public:
    A<int> f1();
    A<char> f2();
};

typedef A<B> AB;
```

Now -

> `./griffin ab.h A B`

> will generate `A<int>` and `A<char>`.

> `./griffin ab.h AB`

> will generate `A<B>`.

> `./griffin ab.h`

> will generate `A<int>`, `A<char>`, and `A<B>`.