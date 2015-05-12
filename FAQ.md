**Q**: Trying to load Robin, I get this cryptic error message:
`ImportError: /usr/lib/libstdc++.so.6: version 'GLIBCXX_3.4.9' not found (required by /usr/lib/librobin-1.0.so)`.

**A**: Your Python is compiled with a different version of compiler than Robin is.
What you can do is either recompile Robin with the same compiler version used to compile Python, or recompile Python with the same compiler version used to compile Robin.