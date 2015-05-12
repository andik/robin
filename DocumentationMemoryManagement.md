Python is garbage-collected. C++ is not. This brings up frequent conflicts when allocating and de-allocating objects dynamically on the heap. In particular, when a C++ programmer writes a function which dynamically allocates and object, it is beyond the scope of the programming language itself to specify how and when should the object be deleted.

Robin, therefore, effects a crude heuristic approach:

  * When a C++ function returns a pointer-to-type, the object is cleaned up using `delete` as soon as there are no more references to it from Python.
  * When a C++ function returns a reference-to-type, it is assumed that the object it references is controlled by another instance, and is therefore never deleted.
  * In addition, when a reference-to-type is returned from an object method, the object used as `this` argument is kept alive for as long as the returned object is alive, even if it is no longer referenced by any Python variable.

Sometimes, of course, you would like to override these decisions. There are two ways to do this: at runtime or at compile-time.

  * At _runtime_. Using the functions `robin.own` and `robin.disown` you can change the "ownership" status of an object. An "owned" object is one which the Python side is responsible for deleting, whereas a "disowned" or "borrowed" object will never be deleted by Robin.
  * At _compile-time_. Attaching a special attribute to a function or method via its Javadoc (Doxygen) block changes the behaviour for all objects returned by it.

```
/**
 * This will cause the function to 
 * always return an owned reference.
 *
 * @par .robin
 * returns owned
 */
A& func(); 

/**
 * This will cause the function to 
 * always return a borrowed reference.
 *
 * @par .robin
 * returns borrowed
 */
A *func(); 
```