[SWIG](http://www.swig.org) is a popular tool for creating C++/Python bindings. SWIG specializes in bindings for C code, but C++ works just as well.

Below is a summary of several properties of SWIG compared to those of Robin.

| **Feature** | **SWIG** | **Robin** |
|:------------|:---------|:----------|
|Fully Automatic|No|Yes|
|User Hints/Annotations|Yes|Yes|
|Target Languages|Python,Perl,PHP,Tcl,Java&more|Python, Ruby (alpha)|
|Documentation|Partial (txt file)|Yes (Python doc)|
|Runtime|Yes|Yes|
|Cross-platform|Not really (many pitfalls)|Yes|
|Added code footprint|Large|Small|
|Added code requires Python libs|Yes|No|
|Added code should be linked with runtime|Yes|No|
|Performance (compared to hand-written code)|About the same|Slower|
|Inter-module Collaboration|Requires some tinkering|Seamless|
|C++ Templates|Partial|Yes|
|C Arrays|Partial|Partial|
|C++ Implicit Conversions|No|Yes|
|Custom Conversions|Yes (implement in C++)|Yes (implement in Python)|
|STL Support|Yes|Yes|
|C++ Exception Handling|Yes|Yes|
|Polymorphism (passing python objects to C functions) |Yes (called directors)|Yes (called interceptors)|
|Custom Operators|Yes (implement in C++)|Yes (implement in Python)|