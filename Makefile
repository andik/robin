ver = 1.0
minor = 4

###
# Installation
###

prefix = /usr/local
exec_prefix = /usr/local
site-packages = /usr/local/lib/python2.4/site-packages
python = python
jython = jython
-include config.mak

cxx ?= g++
shared ?= -shared -fPIC

pydir = $(site-packages)/robin
libdir = $(exec_prefix)/lib
scriptdir = $(prefix)/bin
jardir = $(libdir)/griffin
sopre := ${shell $(python) -c "import griffin; print griffin.sopre"}
soext := ${shell $(python) -c "import griffin; print griffin.soext"}
cpu = ${shell uname -m}
target = ${shell $(python) -c "import griffin; print griffin.arch"}

ifeq ($(multi-platform),1)
plat := ${shell $(python) -c "import griffin; print griffin.platspec"}
py := ${shell $(python) -c "import griffin; print griffin.pyspec"}
endif

install = install -d ${dir $2} && install $1 $2
cp-r = cp -fr
sed = sed
echo = echo

INSTALLABLE_FILES = \
	$(libdir)/$(vpath)$(sopre)robin$(plat)-$(ver)$(py)$(soext) \
	$(libdir)/$(vpath)$(sopre)robin_pyfe$(plat)-$(ver)$(py)$(soext) \
	$(libdir)/$(vpath)$(sopre)robin_stl$(plat)-$(ver)$(py)$(soext) \
	$(scriptdir)/griffin \
	$(jardir)/Griffin.jar \
	$(pydir).pth \
	$(pydir)/robin.py $(pydir)/griffin.py \
	$(pydir)/stl.py \
	${addprefix $(pydir)/robinlib/, \
		__init__.py platform.py \
		config.py \
		robinhelp.py \
		document.py \
		pickle_weakref.py \
		html/__init__.py html/textformat.py \
		argparse.py} \
	$(jardir)/stl.st.xml $(jardir)/stl.tag 

INSTALLABLE_DIRS = $(jardir)/dox-xml $(jardir)/premises

default all:
	scons

install: $(INSTALLABLE_FILES) $(INSTALLABLE_DIRS) ;

$(pydir)/robin.py: robin.py
	$(call install, $<, $@)
	$(sed) -i -e 's@libdir =.*@libdir = "$(libdir)"@' $@

$(pydir)/griffin.py: griffin.py
	$(call install, $<, $@)

$(pydir)/%.py: src/robin/modules/%.py
	$(call install, $<, $@)

$(pydir).pth:
	$(echo) robin > $@

$(libdir)/$(vpath)%$(soext): %$(soext)
	$(call install, $<, $@)

$(scriptdir)/griffin: griffin
	$(call install, $<, $@)
	$(sed) -i -e 's@here =.*@here = os.path.expanduser("$(jardir)")@' $@
	$(sed) -i -e 's@#!/usr/bin/env python@#!$(python-exe)@' $@

$(jardir)/Griffin.jar: Griffin.jar
	$(call install, $<, $@)

$(jardir)/premises: $(jardir)/Griffin.jar premises
	$(cp-r) premises $@

$(jardir)/stl.st.xml: src/griffin/modules/stl/stl.st.xml
	$(call install, $<, $@)

$(jardir)/stl.tag: build/stl.tag
	$(call install, $<, $@)

$(jardir)/dox-xml: build/dox-xml
	$(cp-r) $< $(jardir)

uninstall:
	-rm -f $(INSTALLABLE_FILES)
	-rm -fr $(pydir)
	-rm -fr $(jardir)


###
# Testing
###

extreme_python = src/robin/extreme/python
SELF = PATH=$(PWD):$(PWD)/src/robin/modules:$$PATH \
       DYLD_LIBRARY_PATH=$(PWD) LD_LIBRARY_PATH=$(PWD) \
       PYTHONPATH=$(PWD):$(PWD)/src/robin/modules
. = .

language-test@%:
	$($*)/griffin $G --include --in $(extreme_python)/language.h     \
                $(extreme_python)/kwargs.h                               \
                $(extreme_python)/samename/1/samename.h                  \
	        --out $(extreme_python)/liblanguage_robin.cc             \
	        El DataMembers PrimitiveTypedef EnumeratedValues Aliases \
	        DerivedFromAlias Inners Constructors AssignmentOperator  \
	        Conversions Exceptions Interface Abstract NonAbstract    \
	        Primitives Pointers StandardLibrary Typedefs             \
	        PublicDouble KwClass --module language
	$(cxx) $(shared) $(extreme_python)/liblanguage_robin.cc          \
                $(extreme_python)/language.cc                            \
	        -o $(extreme_python)/liblanguage.so

protocols-test@%:
	$($*)/griffin $G --in $(extreme_python)/protocols.h              \
                $(extreme_python)/samename/2/samename.h                  \
	        --out $(extreme_python)/libprotocols_robin.cc            \
	        Times --import language
	$(cxx) $(shared) $(extreme_python)/libprotocols_robin.cc         \
	        $(extreme_python)/protocols.cc                           \
	        -o $(extreme_python)/libprotocols.so

templates-test@%:
	$($*)/griffin $G --in $(extreme_python)/templates.h              \
	        --out $(extreme_python)/libtemplates_robin.cc
	$(cxx) $(shared) $(extreme_python)/libtemplates_robin.cc         \
		-o $(extreme_python)/libtemplates.so

inheritance-test@%:
	$($*)/griffin $G --in $(extreme_python)/inheritance.h            \
	        --out $(extreme_python)/libinheritance_robin.cc          \
	        --interceptors Functor FunctorImpl mapper mul TaintedVirtual
	$(cxx) $(shared) $(extreme_python)/libinheritance_robin.cc       \
		-o $(extreme_python)/libinheritance.so

hints-test@%:
	$($*)/griffin $G --in $(extreme_python)/hinted.h --include       \
	        --out $(extreme_python)/libhints_robin.cc                \
		--import language                                        \
	        --hints=$(extreme_python)/hint.py Clue Templates
	$(cxx) $(shared) $(extreme_python)/libhints_robin.cc             \
		-o $(extreme_python)/libhints.so -I$(extreme_python)

autocollect-test@%:
	$($*)/griffin $G --in $(extreme_python)/autocollect.h            \
	        --out $(extreme_python)/libautocollect_robin.cc
	$(cxx) $(shared) $(extreme_python)/libautocollect_robin.cc       \
		-o $(extreme_python)/libautocollect.so


memprof-test@%:
	$($*)/griffin $G --in $(extreme_python)/memory.h                 \
	        --out $(extreme_python)/libmemprof_robin.cc
	$(cxx) $(shared) $(extreme_python)/libmemprof_robin.cc           \
	        -o $(extreme_python)/libmemprof.so


TESTS = language-test templates-test protocols-test inheritance-test hints-test autocollect-test memprof-test
TEST_SUITES = LanguageTest STLTest TemplatesTest ProtocolsTest InheritanceTest HintsTest KwargsTest MemoryManagementTest
TESTING_PYTHON = cd $(extreme_python) && $(SELF) $(python)
TESTING_PYTHON_GDB = cd $(extreme_python) && $(SELF) gdb --args $(python)
TESTING_PYTHON_VG = cd $(extreme_python) && $(SELF) valgrind --tool=memcheck $(python)

test: ${addsuffix @., $(TESTS)}
	-rm -f module.tag
	( $(TESTING_PYTHON) test_cases.py $(TEST_SUITES) )

justtest:
	( $(TESTING_PYTHON) test_cases.py $(TEST_SUITES) )

systest: ${addsuffix @scriptdir, $(TESTS)}
	( cd $(extreme_python) && \
	        $(python) test_cases.py $(TEST_SUITES) )

jytest:
	$(jython) tests/tests.py $(ARGS)

# - development tools
interactive:
	$(TESTING_PYTHON)

debug:
	( $(TESTING_PYTHON_GDB) test_cases.py $(TEST_SUITES) )

memcheck:
	( $(TESTING_PYTHON_VG) test_cases.py $(TEST_SUITES) )


###
# Distribution
###
manifest:
	$(MAKE) -n install prefix=/demo exec_prefix=/demo site-packages=/demo \
	  install='install $$1' cp-r=install \
	   | grep '^install' | awk '{ print $$2; }' \
	   | xargs -I {} find {} -type f -o -name .svn -prune -type f \
	   > manifest
	echo Makefile >> manifest
	echo configure >> manifest

.PHONY: distrib

distrib: manifest
	@rm -rf distrib/robin
	mkdir -p distrib/robin
	tar cf - --files-from manifest | ( cd distrib/robin && tar xf - )
	tar zcf distrib/robin-$(ver).$(minor).$(cpu).$(target).tar.gz \
		-C distrib robin

srcdistrib:
	-rm -rf distrib/robin
	mkdir -p distrib
	svn export . distrib/robin
	rm -rf distrib/robin/premises
	tar zcf distrib/robin-$(ver).$(minor).src.tar.gz -C distrib robin
