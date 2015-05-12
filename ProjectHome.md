Robin is an Open-Source project providing quick, hands-free automated bindings of C++ libraries for Python, and possibly some other languages and platforms (such as Ruby, the JVM, and the CLR) in the future. This is achieved by creating a minimal amount of "Glue Code" above the existing routines, along with some "Meta-Data" which, when combined with a thin run-time mechanism exposes the C++ types as native types in the target platform.

Robin's primary ideal is to make bindings as tight as possible, such that usability will resemble that which the user can produce using the original C++ code; Some examples for this are:

  * Imitating C++-style implicit conversions
  * Coupling STL primitives with the primitives of the target language
  * Automatic template instantiation and exposure of template instances
  * Allowing user Python code to derive from a C++ interface class and implement virtual methods