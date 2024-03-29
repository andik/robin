package backend.robin;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import sourceanalysis.Aggregate;
import sourceanalysis.Alias;
import sourceanalysis.ConstCollection;
import sourceanalysis.ContainedConnection;
import sourceanalysis.Entity;
import sourceanalysis.Field;
import sourceanalysis.InappropriateKindException;
import sourceanalysis.InheritanceConnection;
import sourceanalysis.MissingInformationException;
import sourceanalysis.Namespace;
import sourceanalysis.Parameter;
import sourceanalysis.Primitive;
import sourceanalysis.ProgramDatabase;
import sourceanalysis.Routine;
import sourceanalysis.Scope;
import sourceanalysis.SourceFile;
import sourceanalysis.Specifiers;
import sourceanalysis.TemplateArgument;
import sourceanalysis.Type;
import sourceanalysis.TypenameTemplateArgument;
import sourceanalysis.hints.Artificial;
import sourceanalysis.hints.IncludedViaHeader;
import backend.Utils;
import backend.robin.model.ConstructCopy;
import backend.robin.model.DeleteSelf;
import backend.robin.model.Dereference;
import backend.robin.model.ElementKind;
import backend.robin.model.NopExpression;
import backend.robin.model.RegData;
import backend.robin.model.RoutineDeduction;
import backend.robin.model.SimpleType;
import backend.robin.model.StaticMethodCall;
import backend.robin.model.TypeToolbox;
import backend.robin.model.RoutineDeduction.ParameterTransformer;

/**
 * Generates flat wrappers and registration code for Robin.
 */
public class CodeGenerator extends backend.GenericCodeGenerator {

	/**
	 * Constructor for CodeGenerator.
	 * @param program the program database to work on
	 * @param output target for generated wrappers and registration data
	 */
	public CodeGenerator(ProgramDatabase program, Writer output) {
		super(program, output);
		m_separateClassTemplates = true;
		m_uidMap = new HashMap<Object, Integer>();
		m_uidNext = 0;
		m_globalDataMembers = new LinkedList<Field>();
		m_interceptorMethods = new HashSet<Routine>();
		m_downCasters = new LinkedList<String>();
		m_interceptors = new LinkedList<Aggregate>();
		m_entry = new LinkedList<RegData>();

	    m_randomNamespace = generateNamespaceName();
	    
	    m_included_snippets = new TreeSet<String>();

		// Register touchups for special types
		Type voidptr =
			TypeToolbox.makePointer(TypeToolbox.makeConst(Primitive.VOID));
		Filters.getTouchupsMap().put(
				new Type(new Type.TypeNode(Primitive.FLOAT)),
				new Filters.Touchup(
						voidptr,
						"const void *touchup(float val)\n{\n" +
						"\treturn union_cast<void*>(val);\n" +
						"}\n" +
						"float touchdown(const void* val)\n{\n" +
						"\treturn union_cast<float>(val);\n" +
						"}\n"));

		Type doubleptr =
			TypeToolbox.makePointer(TypeToolbox.makeConst(Primitive.DOUBLE));
		Filters.getTouchupsMap().put(
				new Type(new Type.TypeNode(Primitive.DOUBLE)),
				new Filters.Touchup(
						doubleptr,
						"const double *touchup(double val)\n{\n" +
						"\treturn new double(val);\n" +
						"}\n" +
						"double touchdown(const double* val)\n{\n" +
						"\treturn *std::auto_ptr<const double>(val);\n" +
						"}\n"));
		Type longlongptr = TypeToolbox.makePointer(Primitive.LONGLONG);
		Filters.getTouchupsMap().put(
				new Type(new Type.TypeNode(Primitive.LONGLONG)),
				new Filters.Touchup(
						longlongptr,
						"long long *touchup(long long val)\n{\n" +
						"\treturn new long long(val);\n" +
						"}\n" +
						"long long touchdown(long long* val)\n{\n" +
						"\treturn *std::auto_ptr<long long>(val);\n" +
						"}\n"));
		Type ulonglongptr = TypeToolbox.makePointer(Primitive.ULONGLONG);
		Filters.getTouchupsMap().put(
				new Type(new Type.TypeNode(Primitive.ULONGLONG)),
				new Filters.Touchup(
						ulonglongptr,
						"unsigned long long *touchup(unsigned long long val)\n{\n" +
						"\treturn new unsigned long long(val);\n" +
						"}\n" +
						"unsigned long long touchdown(unsigned long long* val)\n{\n" +
						"\treturn *std::auto_ptr<unsigned long long>(val);\n" +
						"}\n"));
	}
	
	/**
	 * Returns an object identifier - for any n invocations of uid(o) with
	 * the same object o, uid obligates to return the same identifier. For
	 * any two unidentical objects s,t, uid guarantees that uid(s)!=uid(t).
	 * @param o
	 * @return
	 */
	private int uid(Object o) {
		//return System.identityHashCode(o); <-- this seems not to do well
		Object got = m_uidMap.get(o);
		if (got == null) {
			m_uidNext++;
			m_uidMap.put(o, new Integer(m_uidNext));
			return m_uidNext;
		}
		else
			return ((Integer)got).intValue();  
	}

	/**
	 * Finds the given class within the global namespace, listing it for
	 * interceptor creation later.
	 *
	 * @param classname the name of the class to create an interceptor for
	 */
	public void investInterceptor(String classname)
	{
		// Go through all of the Subjects, searching for the given name
		for (Aggregate agg: m_subjects) {
            if (!agg.getName().equals(classname)) continue; // not the right class
            try { if (!backend.Utils.isAbstract(agg)) continue; } catch (MissingInformationException e) {}
            if (agg.isTemplated() && m_separateClassTemplates) continue;

            m_interceptors.add( agg );
		}
	}

    /**
     * Goes over all the classes, listing it for interceptor creation later.
     */
    public void autoInvestInterceptor() {
		// Go through all of the Subjects, searching for the given name
    	for (Aggregate agg: m_subjects) {
            try { if (!backend.Utils.isPolymorphic(agg)) continue; } catch (MissingInformationException e) {}
            if (agg.isTemplated() && m_separateClassTemplates) continue;

            m_interceptors.add( agg );
		}
    }

	/**
	 * Collects all the constants of the program.
	 */
	public void collectConstants()
		throws IOException, MissingInformationException
	{
		// collect constants in the global namespace
		collectConstants(m_program.getGlobalNamespace().getScope());
		// collect constants in subject classes
		for (Aggregate aggr: m_subjects) {
			collectConstants(aggr.getScope());
		}
	}
	
	/**
	 * Collects constants of the program.
	 * 
	 * @param scope program scope inside of which to search for constant.
	 * Search will descend to inner scopes of this one as well.
	 */
	protected void collectConstants(Scope<? extends Entity> scope)
		throws IOException, MissingInformationException
	{
		// Collect fields in current scope
		for (ContainedConnection<? extends Entity, Field> connection: scope.getFields()) {
			Field field = (Field)connection.getContained();

			if (Filters.isAvailableStatic(field, connection)) {
				m_globalDataMembers.add(field);
			}
		}
		// Trace constants in innermore namespaces
		for (ContainedConnection<? extends Entity, Namespace> connection: scope.getNamespaces()) {
			Namespace namespace = (Namespace)connection.getContained();
			// - go to recursion
			collectConstants(namespace.getScope());
		}
	}

	/**
	 * Creates a common beginning which is required for all Robin
	 * flat-wrapping-self-registrating files.
	 * @throws IOException
	 */
	public void generatePreface() throws IOException
	{
		m_output.write("#include <memory>\n");
		
		m_output.write("struct RegData\n");
		m_output.write("{\n");
		m_output.write("	const char *name;\n");
		m_output.write("	const char *type;\n");
		m_output.write("	const RegData *prototype;\n");
		m_output.write("	void *sym;\n");
		m_output.write("};\n\n");
		m_output.write("struct PascalString\n");
		m_output.write("{\n");
		m_output.write("	unsigned long size;\n");
		m_output.write("	const char *chars;\n");
		m_output.write("	char buffer[1];\n");
		m_output.write("};\n\n");
		// TODO autoconf specific
		m_output.write("#ifdef SPECIAL_CONVERSION_OPERATION\n" +
			"# define CONVOP(type,self) static_cast<type>(*self)\n" +
			"#else\n" +
			"# define CONVOP(type,self) self->operator type()\n" +
			"#endif\n\n");
		// Win32 specific
		m_output.write("#ifdef _WINDLL\n" +
				"# define EXPORT __declspec(dllexport)\n" +
				"#else\n# define EXPORT\n#endif\n\n");
        m_output.write("#if defined(__GNUC__) && defined(__i386__)\n" +
                       "# define __CDECL __attribute__((cdecl))\n" +
                       "#elif defined(_WIN32)\n" +
                       "# define __CDECL __cdecl\n" +
                       "#else\n" +
                       "# define __CDECL\n" +
                       "#endif\n\n");
		
		m_output.write("typedef void* basic_block;\n");
		m_output.write("typedef void* scripting_element;\n\n");
		m_output.write("typedef void* xfer_float;\n\n");
		m_output.write("template < typename TO, typename FROM > TO union_cast(FROM v) { union { FROM f; TO t; } u; u.f = v; return u.t; }\n\n");
		
        final String callbackvar = 
                "bool (*__robin_callback)(scripting_element twin, " + 
                "RegData *signature, basic_block args[], " +
                "basic_block *result, bool isPure)";
		m_output.write("extern " + callbackvar + ";\n");
		m_output.write(callbackvar + " = 0;\n\n");
	
		m_output.write("\nnamespace " + m_randomNamespace + " {\n\n");
		
		// Special touchup function named same
		m_output.write("template <class T>\n" +
			"struct SameClass {\n" +
			"\tstatic T same(T i) { return i; }\n" +
			"};\n\n");
		
		// Generate touchup code
		for (Filters.Touchup touchup: Filters.getTouchupsMap().values()) {
			m_output.write(touchup.m_touchupCode + "\n");
		}
	}
	
	/**
	 * Writes <code>#include &lt;...&gt;</code> directives to add the required
	 * cofiles to the generated compilation unit.
	 * @throws IOException
	 */
	public void generateIncludeDirectives()
		throws IOException
	{
		List<SourceFile.DeclDefConnection> decldefs = new LinkedList<SourceFile.DeclDefConnection>();
		Set<String> headers = new HashSet<String>();
		
		// Collect class declarations
		for (Aggregate subject: m_subjects) {
			decldefs.add(safeGetDeclaration(subject));
		}
		
		// Collect global routine declarations
		for (Routine routine: m_globalFuncs) {
			if (Filters.isAvailable(routine))
				decldefs.add(safeGetDeclaration(routine));
		}
		
		// Collect typedef declarations
		for (Alias alias: m_typedefs) {
			decldefs.add(safeGetDeclaration(alias));
		}
		
		// Collect declarations of constants
		for (Field field: m_globalDataMembers) {
			decldefs.add(safeGetDeclaration(field));
		}
		
		// Collect the header files that should be included
		for (SourceFile.DeclDefConnection decl: decldefs) {
			// Try to get location of the declaration
			if (decl != null && Filters.isAllowedToInclude(decl)) {
				// Try to get the SourceFile entity for the declaration
				try {
					SourceFile header = decl.getSource();
					// - respect IncludedViaHeader hint
					IncludedViaHeader hint = (IncludedViaHeader)
						header.lookForHint(IncludedViaHeader.class);
					if (hint != null) header = hint.getIncludingHeader();
					if (!header.isExternal())
						headers.add(header.getFullName());
				}
				catch (MissingInformationException e) {
					// Issue a comment indicating the absence of a SourceFile
					m_output.write("// ");
					m_output.write(decl.getSourceFilename());
					m_output.write(" may be missing?\n");
				}
			}
		}		

		// Generate #include directives
		for (String header_abs: headers) {
			String header_rel = Utils.FileTools
				.absoluteToRelative(header_abs, m_outputDirectory);
			m_output.write("#include \"" + header_rel + "\"\n");
		}
	}

    private void addInterceptorBaseConstructor(Routine ctor, Aggregate subject, Aggregate interceptor)
        throws IOException, MissingInformationException
    {
        
    	
    	
    		// TODO: check out the long parameter list, maybe extract a class out of createInterceptor?
        Routine newCtor = (Routine) ctor.clone();
        
        
        
        newCtor.setName(interceptor.getName());
        interceptor.getScope().addMember(
                newCtor, Specifiers.Visibility.PUBLIC, 
                Specifiers.Virtuality.NON_VIRTUAL, Specifiers.Storage.EXTERN);
        
        int minArgs = Utils.minimalArgumentCount(newCtor),
        		maxArgs = Utils.countParameters(newCtor);
        
        for(int nArgs = minArgs; nArgs <= maxArgs; nArgs++) {
        		m_output.write("\t// Interceptor wrapper for constructor with " + nArgs + " out of " + maxArgs + " arguments");
        		m_output.write("\n");
	        m_output.write("\t" + interceptor.getName() + "(");
	        int paramIndex = 0;
	        for (Iterator<Parameter> argIter = newCtor.getParameters().iterator(); argIter.hasNext() && paramIndex < nArgs; paramIndex++) {
	            Parameter param = argIter.next();
	            m_output.write(param.getType().formatCpp(param.getName()));
	            if (argIter.hasNext() && paramIndex < nArgs - 1) m_output.write(", ");
	        }
	        m_output.write(") : " + subject.getName() + "(");
	        paramIndex = 0;
	        for (Iterator<Parameter> argIter = newCtor.getParameters().iterator(); argIter.hasNext() && paramIndex < nArgs; paramIndex++) {
	            Parameter param = argIter.next();
	            m_output.write(param.getName());
	            if (argIter.hasNext() && paramIndex < nArgs - 1) m_output.write(", ");
	        }
	        m_output.write(") {}\n\n");
        }
    }
    
    private void writeInterceptorFunctionThrowsClause(Routine routine) 
    		throws IOException, MissingInformationException {
    		if (routine != null && routine.hasThrowClause()) {
            m_output.write(" throw(");
            boolean first = true;
            for (Aggregate th: routine.getThrows()) {
                if (!first) m_output.write(", ");
                first = false;
                m_output.write(th.getFullName());
            }
            m_output.write(")");
        }
    }

    private void writeInterceptorFunctionHeader(Routine routine, int nArgs)
        throws IOException, MissingInformationException
    {

        m_output.write("\tvirtual ");
        m_output.write(routine.getReturnType().formatCpp());
        m_output.write(" " + routine.getName() + "(");
        int i = 0;
        for (Iterator<Parameter> argIter = routine.getParameters().iterator(); argIter.hasNext() && i < nArgs; i++) {
            Parameter param = argIter.next();
            // write a generic name, in order to avoid name clashes with our own parameters
            m_output.write(param.getType().formatCpp("interceptor_arg" + i));
            m_output.write(" /* " + param.getName() + " */");
            if (argIter.hasNext() && i < nArgs - 1) m_output.write(", ");
        }
        m_output.write(")");
        if (routine.isConst()) m_output.write(" const");
        // Write a throw() clause if required by the interface
        writeInterceptorFunctionThrowsClause(routine);
        m_output.write(" {\n");
    }

    private void writeInterceptorFunctionBasicBlockArgumentArray(Routine routine, int nArgs)
        throws IOException, MissingInformationException
    {
        if(nArgs == 0) {
                // Fix MSVS not allowing 0-length array
                // definition with initializer block
            m_output.write("\t\tbasic_block *args = NULL;\n");
        } else {
            m_output.write("\t\tbasic_block args[] = {\n");
            int i = 0;
            for (Iterator<Parameter> argIter = routine.getParameters().iterator(); argIter.hasNext() && i < nArgs; i++) {
                writeInterceptorFunctionBasicBlockArgument(
                        argIter.next(), i, argIter.hasNext() && i < nArgs - 1
                    );
            }
            m_output.write("\t\t};\n");
        }
    }

    private void writeInterceptorFunctionBasicBlockArgument(Parameter param, int index, boolean moreParams)
        throws IOException, MissingInformationException
    { 	
   	 	Type originalType = TypeToolbox.getOriginalTypeShallow(param.getType());
        Type touchupType = Filters.getTouchup(originalType);
        boolean addReference = Filters.needsExtraReferencing(param.getType()) && touchupType == null;
        m_output.write("\t\t\t" +
                "(" +
                "(" +
                "basic_block (*)" +
                "(" + param.getType().formatCpp() + (addReference ? "*":"") + ")" +
                ")");
       
        if (touchupType == null) {
        	
            m_output.write("SameClass< " + param.getType().formatCpp() + (addReference ? "*":"") + " >" +
                "::same");
        } else {
        		m_output.write(" (" + 
                    touchupType.formatCpp() + 
                    " (*)(" + 
                    param.getType().formatCpp() + ")) ");
                m_output.write("touchup");
        }
        
        // use generic parameter name
        m_output.write(")" +
                "(" + (addReference ? "&":"") +  "interceptor_arg" + index + ")");
        if (moreParams) m_output.write(",");
        m_output.write("\n");
    }

    /**
     * Write the call to __robin_callback
     */
    private void writeInterceptorFunctionCallbackCall(Aggregate subject, Aggregate interceptor, Routine routine, int funcCounter, int nArgs)
        throws IOException, MissingInformationException
    {
        ContainedConnection<? extends Entity, ? extends Entity> uplink = routine.getContainerConnection();
        boolean isPure = 
            (uplink.getVirtuality() == Specifiers.Virtuality.PURE_VIRTUAL);

        // write result definition
        m_output.write("\t\tbasic_block result = 0;\n");

        // write callback call surrounded by and if clause
        m_output.write("\t\t");
        if (!isPure) {
            m_output.write("if (!");
        }
        m_output.write("__robin_callback(twin, ");
        m_output.write("scope_" + 
                interceptor.getScope().hashCode() + 
                " + " +
                funcCounter + 
                ", ");
        m_output.write("args, ");
        m_output.write("&result, ");
        m_output.write("" + isPure + ")");
        if (!isPure) {
            m_output.write(")\n");
            // write the base class call in case of failure to fine frontend implementation
            m_output.write("\t\t\t");
            if (! routine.getReturnType().equals(
                    new Type(new Type.TypeNode(Primitive.VOID)))) {
                m_output.write("return ");
            }
            // in case the child class routine shadows the other routines,
            // we need to write the name of the first class this routine appeared at
            m_output.write(routine.getContainer().getFullName() + "::" + routine.getName() + "(");
            int i = 0;
            for (Iterator<Parameter> argIter = routine.getParameters().iterator(); argIter.hasNext() && i < nArgs; i++) {
            		argIter.next(); // ignore the result, we just need to advance the iterator
            		// write the generated name instead of a real one
                m_output.write("interceptor_arg" + i + 
                    (argIter.hasNext() && i < nArgs - 1 ? ", " : ""));
            }
            m_output.write(");\n");
        }
        else {
            m_output.write(";\n");
        }
    }

    private void writeInterceptorFunctionReturnStatement(Routine routine)
        throws IOException, MissingInformationException
    {
        if (routine.getReturnType().equals(
                new Type(new Type.TypeNode(Primitive.VOID))))
        {
            // void, no return statement
            return;
        }

        final Type touchupType = Filters.getTouchup(routine.getReturnType());
        final Type returnType = touchupType != null ? touchupType : routine.getReturnType();
        
        int parenNest = 0;
        
        m_output.write("\t\treturn ");
    
        if (Filters.needsExtraReferencing(returnType)) {
            m_output.write("*std::auto_ptr< ");
            m_output.write(returnType.formatCpp());
            m_output.write(" >(((");
            m_output.write(routine.getReturnType().formatCpp() + " * (*)(basic_block)) ");
            ++parenNest;
        }
        else {
            m_output.write("( (" + routine.getReturnType().formatCpp() + " (*)(basic_block)) ");
        }
        
        if (touchupType != null) {
            m_output.write("(" + 
                    routine.getReturnType().formatCpp() + 
                    " (*)(" + 
                    touchupType.formatCpp() + ")) ");
            m_output.write("touchdown)(");
        }
        else {
            m_output.write("SameClass< basic_block >::same)(");
        }

        m_output.write("result)");
        
        for (int paren = 0; paren < parenNest; ++paren)
            m_output.write(")");
        
        m_output.write(";\n");
    }
    

    private void writeInterceptorFunctionAddRoutineToGriffinClass(Aggregate interceptor, Routine routine) {
        // NOTE: funcCounter is updated outside this method, in createInterceptor, to reflect this addition
        m_interceptorMethods.add(routine);
        interceptor.getScope().addMember(
                routine, Specifiers.Visibility.PUBLIC, 
                Specifiers.Virtuality.VIRTUAL, Specifiers.Storage.EXTERN);
    }

    private void writeInterceptorFunction(Aggregate subject, Aggregate interceptor, Routine routine, int funcCounter)
        throws IOException, MissingInformationException
    {
        // TODO: could newRoutine be defined only in writeInterceptorFunctionAddRoutineToGriffinClass?
        Routine newRoutine = (Routine) routine.clone();
        writeInterceptorFunctionAddRoutineToGriffinClass(interceptor, newRoutine);
        
        int minArgs = Utils.minimalArgumentCount(routine),
        		maxArgs = Utils.countParameters(routine);
        
        for(int nArgs = minArgs; nArgs <= maxArgs; nArgs++) {
    			m_output.write("\t /* Wrapper for " + routine.getName());
    			m_output.write(" taking " + nArgs + " out of " + maxArgs + " parameters */\n");
	        writeInterceptorFunctionHeader(newRoutine, nArgs); // TODO: should this be 'routine' instead of 'newRoutine'?
	        writeInterceptorFunctionBasicBlockArgumentArray(routine, nArgs);
	        writeInterceptorFunctionCallbackCall(subject, interceptor, routine, funcCounter, nArgs);
	        writeInterceptorFunctionReturnStatement(routine);
	        m_output.write("\t}\n");
	        funcCounter++;
        }

        m_output.write("\n");
    }
    

    private Routine createInterceptorInitFunction() {
        // TODO: maybe this should be named createInitFunction?
        Routine result = new Routine();
        result.setName("_init");
        Primitive scripting_element = new Primitive();
        scripting_element.setName("scripting_element");
        Parameter _init_imp = new Parameter();
        _init_imp.setName("imp");
        _init_imp.setType(new Type(new Type.TypeNode(scripting_element)));
        result.addParameter(_init_imp);
        result.setReturnType(new Type(new Type.TypeNode(Primitive.VOID)));
        return result;
    }
    
    private Routine createInterceptorDowncastFunction() {
        Routine result = new Routine();
        result.setName("_py");
        
        Primitive scripting_element = new Primitive();
        scripting_element.setName("scripting_element");
        result.setReturnType(new Type(new Type.TypeNode(scripting_element)));
        result.addProperty(new Entity.Property(".robin", "returns borrowed"));
        return result;
    }

    private Aggregate createInterceptor(Aggregate subject)
		throws IOException, MissingInformationException
    {
        // This counter counts how many functions come before the unimplemented ones
        int funcCounter = 0;
        // Add the interceptor class to the subjects to wrap
        Aggregate result = new Aggregate();
        result.addHint(new Artificial());
        // TODO Change the name to "I + uid(subject)" and the regdata name to I + name
        result.setName("I" + subject.getName());
        // Add the inheritance from the original interface
        result.addBase(subject, Specifiers.Visibility.PUBLIC);
        ++funcCounter;
        // Add the _init function
        result.getScope().addMember(
                createInterceptorInitFunction(), Specifiers.Visibility.PUBLIC, 
                Specifiers.Virtuality.NON_VIRTUAL, Specifiers.Storage.EXTERN);
        ++funcCounter;
        result.getScope().addMember(
        			createInterceptorDowncastFunction(), Specifiers.Visibility.PUBLIC, 
                Specifiers.Virtuality.NON_VIRTUAL, Specifiers.Storage.EXTERN);
        ++funcCounter;
        
        m_output.write("// Interceptor for " + subject.getFullName() + "\n");
        m_output.write("extern RegData scope_" + result.getScope().hashCode() + "[];\n");
        m_output.write("class " + result.getName() + " : public " + subject.getFullName() + "\n");
        m_output.write("{\n");
        m_output.write("public:\n");
        
        // get the dtor
        Routine dtor = null;
        for (ContainedConnection<Aggregate, Routine> connection: subject.getScope().getRoutines()) {
            final Routine possibleDtor = (Routine) connection.getContained();

            if (possibleDtor.isDestructor()) {
            		dtor = possibleDtor;
            }
        }

        m_output.write("\tvirtual ~" + result.getName() + "()");
        writeInterceptorFunctionThrowsClause(dtor);
        
        m_output.write(" {}\n\n");
        
        // Create base constructor calls
        boolean anyCtors = false;
        for (ContainedConnection<Aggregate, Routine> connection: subject.getScope().getRoutines()) {
            final Routine ctor = (Routine) connection.getContained();

            if (ctor.isConstructor() &&
                (connection.getVisibility() != Specifiers.Visibility.PRIVATE)) {
        
            		addInterceptorBaseConstructor(ctor, subject, result);
                anyCtors = true;
                // allow for default argument ctors
                funcCounter += (Utils.countParameters(ctor) - Utils.minimalArgumentCount(ctor) + 1);
            }
        }

        // - it checks if the class has no constructors user-implemented at all
        //   (i.e. has only compiler-made default constructor)
        if (Utils.hasDefaultConstructor(subject) && !anyCtors) {
            final Routine ctor = new Routine();
            // ctor return type is 'nothing'
            ctor.setReturnType(new Type(null));
            ++funcCounter;
            addInterceptorBaseConstructor(ctor, subject, result);
            
        }
        
        m_output.write("\tvoid _init(scripting_element imp) { twin = imp; }\n\n");
        m_output.write("\tscripting_element _py() { return twin; }\n\n");
        
        // Write functions in interceptor class, and add them to the griffin class
        for (Routine routine: Utils.virtualMethods(subject, m_instanceMap, false)) {
            /* for the odd case of an idiot writing
             * private:
             * 	virtual void method();
             * 
             * we still want to create compilable code
             * 
             */
            if(routine.getContainerConnection().getVisibility() == Specifiers.Visibility.PRIVATE) {
            		continue;
            }
            
            // If the current function has some default arguments, increment
            // the function pointer to the last function
            final int defaultArgumentCount = 
                Utils.countParameters(routine) -
                Utils.minimalArgumentCount(routine);
            
            	writeInterceptorFunction(subject, result, routine, funcCounter);
            	
            // Increment the function pointer counter
            funcCounter += defaultArgumentCount + 1;
        }
        
        // now, we want to add bogus members with public visibility
        // that expose all the members up to 'protected' of parent classes
        
        Map<String, Type> wrappedTypes = new HashMap<String, Type>();
        
        for (Field f: Utils.accessibleFields(subject, m_instanceMap, Specifiers.Visibility.PROTECTED)) {
        		writeInterceptorFieldWrapper(subject, result, f, funcCounter, m_instanceMap, wrappedTypes);
        }
        
        // Write private sections of class
        m_output.write("private:\n");
        m_output.write("\tscripting_element twin;\n");
        m_output.write("};\n\n");
			
        return result;
    }
	
	private void writeInterceptorFieldWrapper(Aggregate subject,
			Aggregate result, Field originalField, int funcCounter, Map<String, Aggregate> templateInstances, Map<String, Type> newTypes) 
	throws IOException, MissingInformationException {
		
		Type type = originalField.getType();
		if(Filters.isArray(type)) {
			return; // wrapping arrays is not supported
		}
		
		m_output.write("\t/* Wrapper for field " + originalField.getFullName() + "*/");
		m_output.write("\n");
		// add a typedef to make field type public
		String newTypeName = "" + uid(originalField);
		
		if(newTypes.containsKey(newTypeName) == false) {
			Alias interceptedTypeBase = new Alias();
			interceptedTypeBase.setAliasedType(type);
			interceptedTypeBase.setName("intercepted_" + newTypeName);
			result.getScope().addMember(interceptedTypeBase, Specifiers.Visibility.PUBLIC);
			Type t = new Type(new Type.TypeNode(interceptedTypeBase));
			newTypes.put(newTypeName, t);
			m_output.write("\ttypedef " + 
					removeUnneededConstness(type).formatCpp( 
					t.getBaseType().getName()));					
			m_output.write(";\n");
		}
		
		Type interceptedType = newTypes.get(newTypeName);
		
		Field interceptorF = (Field)originalField.clone();
		interceptorF.setFieldType(Field.FieldType.WRAPPED);	
		interceptorF.setType(interceptedType);
		
		result.getScope().addMember(
                interceptorF, Specifiers.Visibility.PUBLIC, 
                originalField.getContainerConnection().getStorage());
		
		
		
		
		
		
		// using an extension of generateFlatWrapper because it fits our needs perfectly
		generateFlatWrapper(interceptorF, true, true);
		
	}

	

	/**
	 * Creates definitions of interceptor classes to wrap the classes marked
	 * for interceptor creation, so that they can be implemented or extended
	 * in the frontend.
	 * @throws IOException
	 * @throws MissingInformationException
	 */
	public void generateInterceptors()
		throws IOException, MissingInformationException
	{
		// New classes to add
		Set<Aggregate> newSubjects = new HashSet<Aggregate>();
		
		// Generate interceptor class decleration
		for (Aggregate subject: m_interceptors) {

            // Check if the class is a template instantiation, and if so skip it
            if (subject.isSpecialized()) continue;
            
            // Skip when there are no virtual methods
            if (Utils.virtualMethods(subject, m_instanceMap, true).isEmpty()) continue;

            // Skip when there are only private constructors
            if (!Filters.isClassExtendible(subject)) continue;
        
            newSubjects.add( createInterceptor(subject) );
		}
		
		// Add all of the new subjects to the subjects set
		m_subjects.addAll(newSubjects);
	}
	
	/**
	 * Generates static method wrappers for every routine
	 * This is done by adding a static version of every method that accepts
	 * *this as first argument
	 */
	public void generateStaticRoutines() throws IOException 
	{
		// Generate routine wrappers for class methods
		for (Aggregate subject: m_subjects) {
                        if (!Filters.isAvailable(subject)) {
                          continue;
                        }
			
			RoutineDeduction.ParameterTransformer thisParam = 
				new RoutineDeduction.ParameterTransformer(
						TypeToolbox.makeReference(subject),
						new NopExpression(), new SimpleType(subject, null, "&"));

			// Grab all the routines from subject aggregate
			for (ContainedConnection<Aggregate, Routine> connection: subject.getScope().getRoutines()) {
				Routine routine = (Routine)connection.getContained();

				if (Filters.isAvailableStatic(routine)) {
					// - align the number of arguments
					int minArgs = Utils.minimalArgumentCount(routine),
						maxArgs = Utils.countParameters(routine);
					for (int nArguments = minArgs; nArguments <= maxArgs;
							++nArguments) {
						try {
							List<RoutineDeduction.ParameterTransformer> paramf =
								RoutineDeduction.deduceParameterTransformers(routine.getParameters().iterator(), nArguments);
							RoutineDeduction.ParameterTransformer retf =
								RoutineDeduction.deduceReturnTransformer(routine);
							String name = "static_" + uid(routine) + "r" + nArguments;
							paramf.add(0, thisParam);
							m_output.write(Formatters.formatFunction(routine, name, 
									retf, paramf, new StaticMethodCall(routine)));
							m_entry.add(new RegData(Utils.cleanFullName(routine), 
									retf.getRegDataType(), 
									name+"_proto", name));
						}
						catch (MissingInformationException e) {
							System.err.println("*** Warning: skipped static wrapper for method " + routine.getFullName());
						}
					}
					
				}
			}
		}		
	}
	
	/**
	 * Create wrappers for clasess that were found during collect().
	 * @throws IOException
	 * @throws MissingInformationException
	 */
	public void generateRoutineWrappers()
		throws IOException, MissingInformationException
	{ 
		// Generate routine wrappers for class methods
		for (Aggregate subject: m_subjects) {
                        if (!Filters.isAvailable(subject)) {
                          continue;
                        }
			boolean isAbstractClass = Utils.isAbstract(subject, m_instanceMap);
			boolean mustHaveCtor = Utils.hasDefaultConstructor(subject);
			boolean ctors = false;
			boolean hasOutput = Utils.hasOutputOperator(subject, m_program);
			boolean hasAssignment = Filters.isAssignmentSupportive(subject);
			boolean hasClone = Filters.isCloneable(subject);
			List<Routine> additional = Utils.findGloballyScopedOperators(subject, m_program);
			// Create upcasts
			generateUpDownCastFlatWrappers(subject);
			// Grab all the routines from subject aggregate
			for (ContainedConnection<Aggregate, Routine> connection: subject.getScope().getRoutines()) {
				Routine routine = (Routine)connection.getContained();
				if (routine.isConstructor() && isAbstractClass) continue;
				if (connection.getVisibility() == Specifiers.Visibility.PUBLIC
					&& !routine.isDestructor()
					&& Filters.isAvailable(routine)) {
					// - align the number of arguments
					int minArgs = Utils.minimalArgumentCount(routine),
						maxArgs = Utils.countParameters(routine);
					for (int nArguments = minArgs; nArguments <= maxArgs;
							++nArguments) {
						if (! m_interceptorMethods.contains(routine))
							generateFlatWrapper(routine, nArguments, true);
						generateRegistrationPrototype(routine, nArguments, true);
					}
					ctors = ctors || routine.isConstructor();
				}
			}
			// Grab members from subject aggregate
			for (ContainedConnection<Aggregate, Field> connection: subject.getScope().getFields()) {
				Field field = (Field)connection.getContained();
				if (Filters.isAvailable(field, connection))
					generateFlatWrapper(field, true, false);
			}
			// More special stuff
			if (!ctors && mustHaveCtor && !isAbstractClass) {
				generateSpecialDefaultConstructorWrapperAndPrototype(subject);
			}
			if (hasAssignment) {
				generateSpecialAssignmentOperator(subject);
			}
			if (hasClone) {
				generateSpecialCloneMethod(subject);
			}
			if (hasOutput) {
				generateSpecialOutputOperator(subject);
				generateSpecialStringConverter(subject);
			}
			for (Routine routine: additional) {
				if (!m_globalFuncs.contains(routine) 
						&& Filters.isAvailable(routine))
					m_globalFuncs.add(routine);
			}
			generateSpecialDestructor(subject);
		}
		
		// Generate wrappers for encapsulated aliases
		for (Alias alias: m_typedefs) {
			if (Filters.needsEncapsulation(alias)) {
				generateFlatWrapper(alias);
				generateRegistrationPrototype(alias);
			}
		}
		
		// Generated global function wrappers
		for (Routine func: m_globalFuncs) {
			if (Filters.isAvailable(func)) {
				// - align the number of arguments
				int minArgs = Utils.minimalArgumentCount(func),
					maxArgs = Utils.countParameters(func);
				for (int nArguments = minArgs; nArguments <= maxArgs;
						++nArguments) {
					generateFlatWrapper(func, nArguments, false);
					generateRegistrationPrototype(func, nArguments, false);
				}
			}
		}
	}

	/**
	 * Creates wrappers for all the constants previously found during
	 * collectConstants().
	 */
	public void generateConstantWrappers()
		throws IOException, MissingInformationException
	{
		for (Field global: m_globalDataMembers) {
			generateFlatWrapper(global, false, false);
		}
	}
	
	/**
	 * Creates wrappers for enumerated types that were found during collect().
	 * @throws IOException
	 */
	public void generateEnumeratedTypeWrappers()
		throws IOException
	{
		for (sourceanalysis.Enum subject: m_enums) {
			// Generate a fine prototype here
			generateFlatWrapper(subject);
			generateRegistrationPrototype(subject);
		}
	}

	public void generateEntry()
		throws IOException, MissingInformationException
	{
		List<Aggregate> sorted_subjects = topologicallySortSubjects(true);
		
		for (Aggregate subject: m_subjects) {
                        if (!Filters.isAvailable(subject)) {
                          continue;
                        }
			// Generate the interface for the class
			generateRegistrationPrototype(subject.getScope(),
				Utils.cleanFullName(subject),
				subject.getBases(), 
				Utils.isAbstract(subject, m_instanceMap),
				Utils.hasDefaultConstructor(subject),
				Filters.isAssignmentSupportive(subject),
				Filters.isCloneable(subject),
				Utils.hasOutputOperator(subject, m_program), true, true,
				Utils.findGloballyScopedOperators(subject, m_program));
		}
		
        // close the random namespace and generate a using directive
		m_output.write("\n}  // end of " + m_randomNamespace + " namespace\n\n");
        m_output.write("using namespace " + m_randomNamespace + ";\n\n");
		
		// Generate main entry point
		m_output.write("extern \"C\" EXPORT RegData entry[];\n\n");
		m_output.write("RegData entry[] = {\n");
		// - enter enumerated types (these should all appear BEFORE function protos)
		for (sourceanalysis.Enum subject: m_enums) {
			m_output.write("\t{\"");
			m_output.write(Utils.cleanFullName(subject));
			m_output.write("\", \"enum\", ");
			m_output.write("enumerated_" + subject.hashCode());
			m_output.write("},\n");
		}
		// - enter typedefs
		for (Alias subject: m_typedefs) {
			// do not generate prototypes for typedefs that weren't
			// declared in headers
			if(!Filters.isDeclared(subject)) {
				continue;
			}
			Type aliased = subject.getAliasedType();
			if (aliased.isFlat()) {
				m_output.write("\t{\"");
				m_output.write(Utils.cleanFullName(subject));
				if (Filters.needsEncapsulation(subject,false)) {
					m_output.write("\", \"class\", alias_" + uid(subject));
					m_output.write("},\n");
				}
				else {
					m_output.write("\", \"=");
					m_output.write(Utils.actualBaseName(aliased));
					m_output.write("\", 0},\n");
				}
			}
		}
		// - enter classes
		for (Aggregate subject: sorted_subjects) {
                        if (!Filters.isAvailable(subject)) {
                          continue;
                        }
			m_output.write("\t{\"");
			m_output.write(Utils.cleanFullName(subject));
			m_output.write("\", \"class\", ");
			m_output.write("scope_" + subject.getScope().hashCode());
			m_output.write("},\n");
		}
		// - enter global functions
		for (Routine subject: m_globalFuncs) {
			if (Filters.isAvailable(subject)) {
				generateRegistrationLine(subject, 0, false);
			}
		}
		for (Iterator<RegData> iter = m_entry.iterator(); iter.hasNext(); ) {
			m_output.write(Formatters.formatRegData(iter.next()) + "\n");
		}
		// - enter global data members
		for (Field field: m_globalDataMembers) {
			// Create an entry for each public data member
			if (Filters.isAvailable(field, field.getContainerConnection()))
				generateRegistrationLine(field, false);
		}
		// - enter downcast functions
		for (String downCaster: m_downCasters) {
			m_output.write("{ " + downCaster + " },\n");
		}
		m_output.write(END_OF_LIST);
		m_output.flush();
	}
	
	/**
	 * Writes the name of the base type from a <b>flat</b> type given. C++
	 * formatting of base type, including template arguments, is written to
	 * the CodeGenerator's output.
	 * @param type flat type to be written out
	 * @throws IOException if errors occur during output operation
	 */
	private void writeFlatBase(Type type) throws IOException
	{
		type = TypeToolbox.getOriginalType(type);
		Entity base = type.getBaseType();
		// express basename
		m_output.write(Utils.cleanFullName(base));
		// express template
		TemplateArgument[] templateArgs = type.getTemplateArguments();
		if (templateArgs != null) {
			m_output.write("< ");
			for (int i = 0; i < templateArgs.length; i++) {
				TemplateArgument templateArgument = templateArgs[i];
				if (i > 0) m_output.write(",");					
				if (templateArgument == null) m_output.write("?");	
				else if (templateArgument instanceof TypenameTemplateArgument)
					m_output.write (Utils.cleanFormatCpp(
						((TypenameTemplateArgument)templateArgument)
						.getValue(),""));
				else m_output.write(templateArgument.toString());
			}
			m_output.write(" >");
		}
	}
	
	/**
	 * Writes the name of the base type from a <b>flat</b> type given using
	 * writeFlatBase(), and decorates the name with a prefix which is useful
	 * when trying to identify the type.
	 * <p>Currently, enumerated types are prefixed with '#', and other
	 * types are written as are.</p>
	 * @param type flat type to be written out
	 * @throws IOException if errors occur during output operation
	 */
	private void writeDecoratedFlatBase(Type type) throws IOException
	{
		if (type.getBaseType() instanceof Alias) {
			writeDecoratedFlatBase(((Alias)type.getBaseType()).getAliasedType());
		} else { 
			if (type.getBaseType() instanceof sourceanalysis.Enum) {
				m_output.write("#");
			}
			writeFlatBase(type);
			if (type.isArray())
				m_output.write("[]");
		}
	}

	
	
	/**
	 * Generates call code for a method or function. 
	 * @param routine entity for method or function to be wrapped in code
	 * @param nArguments number of arguments from the parameter list to consider
	 * @param with if <b>true</b> - the generated code will provide an
	 * instance invocation (self-&gt;) when applicable. If <b>false</b> - the
	 * routine will be treated as a global function or a public static method.
	 * @throws IOException if output fails
	 * @throws MissingInformationException if type elements are missing from
	 * prototype
	 */
	private void generateFlatWrapper(Routine routine, int nArguments, boolean with)
		throws IOException, MissingInformationException
	{
		m_output.write("/*\n * "); m_output.write(routine.getFullName());
		
		if(routine.getRoutineType() == Routine.RoutineType.STATIC_CALL_WRAPPER) {
			m_output.write("\n * Wraps non-static method " + routine.getFullName());
		}
		
		m_output.write("\n * returns " + routine.getReturnType().toString());
		m_output.write("\n * " + Formatters.formatLocation(routine));
		m_output.write("\n */\n");
	
		Entity thisArg = null;
		if (routine.hasContainer() && with) {
			thisArg = routine.getContainer();
			if (! (thisArg instanceof Aggregate)) thisArg = null;
		}
		
		Type returnType = routine.getReturnType();
		String wrapperName = "routine_" + uid(routine) 
			+ (with ? "r" : "s") + nArguments;
		
		
		if (routine.isConstructor()) {
			// This is a constructor
			m_output.write(thisArg.getFullName()
					+ "* __CDECL " + wrapperName);
		}
		else {
			m_output.write(Formatters.formatDeclaration(returnType, wrapperName, ElementKind.FUNCTION_CALL, true));
		}
		
		// Construct parameters fitting for the flat purpose
		boolean first = true;
		boolean staticParam = false;
		m_output.write("(");
		if (thisArg != null && !routine.isConstructor()) {
			// - generate a *this
			if (routine.isConst()) m_output.write("const ");
			m_output.write(thisArg.getFullName());
			m_output.write(" *self");
			first = false;
		} else if(routine.getRoutineType() == Routine.RoutineType.STATIC_CALL_WRAPPER) {
			// - generate a *this for imaginary functions
			if (routine.isConst()) m_output.write("const ");
			m_output.write(routine.getContainer().getFullName());
			m_output.write(" *self");
			first = false;
		}

		if (!first && nArguments > 0) m_output.write(", ");
		if(routine.getRoutineType() == Routine.RoutineType.STATIC_CALL_WRAPPER 
				&& !staticParam && nArguments > 0) {
			staticParam = true;
		}
		
		List<ParameterTransformer> paramf = RoutineDeduction
			.deduceParameterTransformers(routine.getParameters().iterator(), nArguments);
		ParameterTransformer retf = RoutineDeduction.deduceReturnTransformer(routine);
		m_output.write(Formatters.formatParameters(paramf));
		m_output.write(")\n");
		
		// Generate the body
		int parenNest = 0;
		m_output.write("{\n\t");
		
		StringBuffer invocation = new StringBuffer();
		if (routine.isConstructor()) {
			invocation.append("return new " + thisArg.getFullName());
		}
		else {
			// - write function name to call
			if (thisArg != null || routine.getRoutineType() == Routine.RoutineType.STATIC_CALL_WRAPPER)
				if (routine.isConversionOperator())
					invocation.append(conversionOperatorSyntax(routine, "self"));
				else if (routine.getName().equals("operator==") || 
						  routine.getName().equals("operator!="))
					invocation.append("*self " +    // @@@ STL issue workaround
							routine.getName().substring("operator".length()));
				else {
					invocation.append("self->");
					if(routine.getRoutineType() == Routine.RoutineType.STATIC_CALL_WRAPPER) {
						invocation.append(routine.getFullName());
					} else {
						invocation.append(routine.getName());
					}
				}
			else
				invocation.append(routine.getFullName());
		}
		// - generate call parameters
		if (!routine.isConversionOperator()) {
			first = true;
			staticParam = false;
			
			invocation.append("("); parenNest++;
			invocation.append(Formatters.formatArguments(paramf));
		}
		while (parenNest > 0) {
			invocation.append(")");
			parenNest--;
		}
		m_output.write(retf.getBodyExpr().evaluate(invocation.toString()));
		m_output.write(";\n}\n");
		m_output.flush();
	}
	
	/**
	 * Generates constant integers which contain the values for each of the
	 * enumerated constants.
	 * @param enume enumerated type for which to generate wrapper
	 * @throws IOException if an output error occurs.
	 */
	private void generateFlatWrapper(sourceanalysis.Enum enume) throws IOException
	{
		m_output.write("/*\n * enum "); m_output.write(enume.getFullName());
		m_output.write("\n */\n");
		// Write the enumerated constants
		for (sourceanalysis.Enum.Constant constant: enume.getConstants()) {
			m_output.write("int const_" + constant.hashCode());
			m_output.write(" = (int)");
			if (enume.hasContainer()) {
				m_output.write(enume.getContainer().getFullName());
				m_output.write("::");
			}
			m_output.write(constant.getLiteral());
			m_output.write(";\n");
		}
		m_output.flush();
	}
	
	/**
	 * Generate routines for typedef - aliasing/unaliasing of types. 
	 * @param alias
	 * @throws IOException
	 */
	private void generateFlatWrapper(Alias alias) throws IOException, MissingInformationException
	{
		m_output.write("/*\n * typedef ");
		m_output.write(alias.getFullName());
		m_output.write("\n */\n");
		// Create a constructor from aliased type
		ParameterTransformer valf = 
			RoutineDeduction.deduceParameterTransformer(alias.getAliasedType());
		ParameterTransformer retf =
			new ParameterTransformer(TypeToolbox.makePointer(alias),
					new ConstructCopy(alias), new SimpleType(alias, null, "*"));
		List<RoutineDeduction.ParameterTransformer> paramf = new ArrayList<RoutineDeduction.ParameterTransformer>();
		paramf.add(valf);
		String ctor = "routine_alias_" + uid(alias);
		m_output.write(Formatters.formatFunction(alias, ctor, retf, paramf,
				new NopExpression()));
		// Create an access method to retrieve stored type
		valf = new ParameterTransformer(TypeToolbox.makePointer(alias), new Dereference(), new SimpleType(alias, null, "*"));
		retf = RoutineDeduction.deduceReturnTransformer(alias.getAliasedType(), ElementKind.VARIABLE);
		paramf.set(0, valf);
		String as = "routine_unalias_" + uid(alias);
		m_output.write(Formatters.formatFunction(null, as, retf, paramf, new NopExpression()));
		// Create a destructor
		retf = RoutineDeduction.deduceReturnTransformer(new Type(new Type.TypeNode(Primitive.VOID)));
		String dtor = "dtor_alias_" + uid(alias);
		m_output.write(Formatters.formatFunction(null, dtor, retf, paramf, new DeleteSelf()));
	}
	

	
	/**
	 * Generates an accessor function for a program variable.
	 * @param field
	 * @param fieldType - the type of the field to generate an accessor for
	 * @param with if <b>true</b> - the generated code will provide an
	 * instance invocation (self-&gt;) when applicable. If <b>false</b> - the
	 * field will be treated as a global variable or a public static member.
	 * @param forInterceptor if true, the method is generated for an intercepted field
	 * @throws IOException
	 * @throws MissingInformationException
	 */
	private void generateFlatWrapper(Field field, boolean with, boolean forInterceptor) 
		throws IOException, MissingInformationException
	{
		String tabs = forInterceptor ? "\t" : "";
		String fors = with ? "f" : "s";
		m_output.write(tabs + "/*\n" + tabs +" * var ");
		m_output.write(field.getFullName());
		m_output.write("\n" + tabs + " * of type " + field.getType());
		m_output.write("\n" + tabs +" * " + Formatters.formatLocation(field));
		m_output.write("\n " + tabs + "*/\n");
		// Get some information about the type
		
		// Create a get accessor
		Entity container = field.getContainer();
		String accessorName = "data_get_" + uid(field) + fors;
		
		Field.FieldType fieldType = field.getFieldType();
		
		
		Type type = field.getType();
		boolean wrappingInterceptorGetter = (fieldType == Field.FieldType.WRAPPED && !forInterceptor);
		Entity base = type.getBaseType();
		
		String thisArg;
		if(forInterceptor) {
			thisArg = "";
		} else {
			thisArg = container instanceof Aggregate ? 
					container.getFullName() + " *self" : "";
		}

		RoutineDeduction.ParameterTransformer retf =
			RoutineDeduction.deduceReturnTransformer(type, ElementKind.VARIABLE);
		String decl = Formatters.formatDeclaration(type, accessorName, ElementKind.VARIABLE,
				wrappingInterceptorGetter, false);
		
		// - generate accessor function header
		m_output.write(tabs + decl
				+ "(" + thisArg + ")");
		// - generate accessor function body
		m_output.write(tabs + " { ");
		
		if(wrappingInterceptorGetter) {
			// touchup/touchdown were taken care of in the generated wrapper
			m_output.write("return self->" + accessorName + "()");
		} else {
		 	m_output.write(retf.getBodyExpr().evaluate(Formatters.formatMember(field, !forInterceptor)));
		}
		m_output.write("; }\n");
		// Create a set accessor
		if (Filters.hasSetter(field)) {
			accessorName = "data_set_" + uid(field) + fors;
			// - generate accessor function header
			m_output.write(tabs + "void " + accessorName + "(");
			if(!forInterceptor) {
				m_output.write(thisArg + ", ");
			}
			RoutineDeduction.ParameterTransformer paramf =
				RoutineDeduction.deduceParameterTransformer(type);
			m_output.write(paramf.getPrototypeType().formatCpp("newval") + ") ");
			// - generate accessor function body
			m_output.write("{ ");
			if(fieldType == Field.FieldType.WRAPPED && !forInterceptor) {
				m_output.write("self->" + accessorName + "(newval)");
			} else {
				m_output.write(Formatters.formatMember(field, !forInterceptor) 
					+ " = ");
				m_output.write(paramf.getBodyExpr().evaluate("newval"));
			}
	
			m_output.write("; }\n");
			
			if(!forInterceptor) {
				// - generate regdata
				if (base instanceof Alias)
					base = TypeToolbox.getOriginalType(type).getBaseType();
				m_output.write("RegData sink_" + uid(field) + fors + "_proto[] = {\n");
				m_output.write("{\"newval\", \"" + base.getFullName() + "\" ,0,0},\n");
				m_output.write(END_OF_LIST);
			}
		}
	}
	
	/**
	 * Generates an array of pointers which describes the function.
	 * @param routine routine being described for registration
	 * @param nArguments see generateFlatWrapper(Routine,int,boolean)
	 */
	private void generateRegistrationPrototype(Routine routine, int nArguments, 
			boolean with) throws IOException, MissingInformationException
	{
		m_output.write("RegData routine_" + uid(routine) 
			+ (with ? "r": "s") + nArguments + "_proto[] = {\n");
		// Go through arguments but not more than nArguments entries
		int argCount = 0;
		for (Iterator<Parameter> pi = routine.getParameters().iterator(); 
				argCount < nArguments && pi.hasNext(); ++argCount) {
			Parameter parameter = pi.next();
			// Write name
			m_output.write("\t{\"");
			m_output.write(parameter.getName());
			m_output.write("\", ");
			// Write type
			// - get attributes
			Type type = TypeToolbox.getOriginalType(parameter.getType());
			Entity base = type.getBaseType();
			int pointers = type.getPointerDegree();
			boolean reference = type.isReference();
			boolean output = Filters.isOutputParameter(parameter);
			// - begin name
			m_output.write("\"");
			if (!(base instanceof Primitive)) {
				// - write references
				if (output) {
					m_output.write(">");
					// One pointer is for the ouput parameters.
					pointers--;
				}
				else if (reference) m_output.write("&");
				// - write pointers
				for (int ptr = 0; ptr < pointers; ++ptr) m_output.write("*");
				// - express extra referencing
				if (Filters.needsExtraReferencing(type)) m_output.write("&");
			}
			// - special care for char *
			if (base instanceof Primitive && base.getName().equals("char")) {
				if (pointers > 0) m_output.write("*");
			}
			// - write base type name
			writeDecoratedFlatBase(type);
			m_output.write("\", 0},\n");
		}
		m_output.write(END_OF_LIST);
		m_output.flush();
	}

	/**
	 * Generates an array of integral values which describes the enumerated
	 * type.
	 * @param enume enumerated type to describe
	 */
	private void generateRegistrationPrototype(sourceanalysis.Enum enume)
		throws IOException
	{
		m_output.write("RegData enumerated_" + enume.hashCode() + "[] = {\n");
		// Write the enumerated constants
		for (sourceanalysis.Enum.Constant constant: enume.getConstants()) {
			m_output.write("\t{ \"");
			m_output.write(constant.getLiteral());
			m_output.write("\", 0, 0, (void*)&const_"
				+ constant.hashCode());			
			m_output.write(" },\n");
		}
		m_output.write(END_OF_LIST);
		m_output.flush();
	}
	
	/**
	 * Generates a proxy class registration prototype for an encapsulated
	 * typedef.
	 * @param alias typedef element to describe
	 * @throws IOException if an output error occurs
	 * @see needsEncapsulation()
	 */
	private void generateRegistrationPrototype(Alias alias)
		throws IOException
	{
		// Register a proxy class
		m_output.write("RegData alias_" + uid(alias) + "[] = {\n");
		// - ctor
		m_output.write("\t{ \"^\", \"constructor\", routine_alias_" 
			+ uid(alias) + "_proto, (void*)&routine_alias_"
			+ uid(alias) + "},\n");
		// - accessor
		m_output.write("\t{ \"as\", \""
			+ Utils.cleanFullName(alias.getAliasedType().getBaseType()));
		m_output.write("\", routine_unalias_" + uid(alias)
			 + "_proto, (void*)&routine_unalias_" + uid(alias) + "},\n");
		// - dtor
		m_output.write("\t{ \".\", \"destructor\", 0, (void*)&dtor_alias_" + uid(alias));
		m_output.write(" },\n");
		m_output.write(END_OF_LIST);
	}
	
	/**
	 * Generates a compiler-generated default constructor wrapper for a class.
	 * @param subject
	 * @throws IOException
	 */
	private void 
		generateSpecialDefaultConstructorWrapperAndPrototype(Aggregate subject)
		throws IOException
	{
		m_output.write(Utils.cleanFullName(subject));
		m_output.write("* __CDECL ctor_" + uid(subject.getScope()));
		m_output.write("() { return new ");
		m_output.write(Utils.cleanFullName(subject));
		m_output.write("; }\n");
	}

	private void generateSpecialAssignmentOperator(Aggregate subject)
		throws IOException
	{
		String classname = Utils.cleanFullName(subject);
		
		m_output.write("void __CDECL assign_" + uid(subject.getScope()));
		m_output.write("(");
		m_output.write(classname + " *self, ");
		m_output.write(classname + " *other)");
		m_output.write(" { *self = *other; }\n");
		m_output.write("RegData assign_" + uid(subject.getScope())
			+ "_proto[] = {\n");
		m_output.write("\t{\"other\", \"*" + classname + "\", 0, 0},\n");
		m_output.write(END_OF_LIST);
	}
	
	private void generateSpecialCloneMethod(Aggregate subject)
		throws IOException
	{
		String classname = Utils.cleanFullName(subject);
		
		m_output.write(classname + "* __CDECL clone_" + uid(subject.getScope()));
		m_output.write("(" + classname + " *self) { return ");
		m_output.write(" new " + classname + "(*self); }\n");
	}
	
	/**
	 * Generates a destructor wrapper for a class.
	 * @param subject
	 * @throws IOException
	 */
	private void generateSpecialDestructor(Aggregate subject)
		throws IOException
	{
		m_output.write("void __CDECL dtor_" + uid(subject.getScope()));
		m_output.write("(" + Utils.cleanFullName(subject) + " *self)");
		m_output.write(" { delete self; }\n");
	}
	
	/**
	 * Writes an output operator wrapper - currently prints to std::cerr.
	 * @param subject class for which to generate output wrapper.
	 * @throws IOException
	 */
	private void generateSpecialOutputOperator(Aggregate subject)
		throws IOException
	{
		if (!m_included_snippets.contains("iostream")) {
			m_output.write("}\n\n"
			             + "#include <iostream>\n\n"
			             + "namespace " + m_randomNamespace + " {\n\n");
			m_included_snippets.add("iostream");
		}
        m_output.write("void __CDECL output_" + uid(subject.getScope())
		             + "("
		             + Utils.cleanFullName(subject)
		             + " *self) { std::cerr << *self; }\n\n");
	}
	
	/**
	 * Writes a suggested conversion to string - by outputting object to
	 * a std::stringstream and acquiring the resulting string.
	 * @param subject class for which to generate string conversion
	 * @throws IOException
	 */
	private void generateSpecialStringConverter(Aggregate subject)
		throws IOException
	{
		// Include STL's stringstream
		if (!m_included_snippets.contains("sstream")) {
			m_output.write("}\n\n"
		                 + "#include <sstream>\n\n"
		                 + "namespace " + m_randomNamespace + " {\n\n");
			m_included_snippets.add("sstream");
		}
		// Define conversion to Pascal string
		if (!m_included_snippets.contains("pascalstring_ctor")) {
			m_output.write("inline struct PascalString *toPascal(const std::string& cpp)\n"
		                 + "{ unsigned long size = (unsigned long)cpp.size();\n"
		                 + "  PascalString *pascal_string = (PascalString*)\n"
	                     + "    malloc(sizeof(PascalString) + size);\n"
		                 + "  pascal_string->size = size; pascal_string->chars = pascal_string->buffer;\n"
		                 + "  memcpy(pascal_string->buffer, cpp.c_str(), size);\n"
		                 + "  return pascal_string;\n}\n");
			m_included_snippets.add("pascalstring_ctor");
		}
		// Write operator
		m_output.write("struct PascalString *toString_" 
						+ uid(subject.getScope()));
		m_output.write("(" + Utils.cleanFullName(subject) + " *self)"
                     + " { std::stringstream ss; ss << *self;\n"
                     + " return toPascal(ss.str()); }\n\n");
	}
	
	/**
	 * Generates up-cast and down-cast wrappers for a class.
	 * 
	 * @param subject class
	 * @throws IOException if output operation fails
	 * @throws MissingInformationException 
	 */
	private void generateUpDownCastFlatWrappers(Aggregate subject)
		throws IOException, MissingInformationException
	{
		for (InheritanceConnection connection: subject.getBases()) {
			if (connection.getVisibility() == Specifiers.Visibility.PUBLIC) {
				 Aggregate base = (Aggregate)connection.getBase();
				 String basename = "";
					 
				 if (base.isTemplated()) {
				 	basename = Utils.templateCppExpression(base, 
				 		connection.getBaseTemplateArguments());
				 }
				 else {
				 	basename = Utils.cleanFullName(base);
				 }
				 
				 String derivedname = Utils.cleanFullName(subject);
				 String derived2base = uid(subject.getScope()) + "_to_" + uid(base.getScope());
				 String base2derived = uid(base.getScope()) + "_to_" + uid(subject.getScope());
				 
				 m_output.write(
						 basename + "* __CDECL upcast_" + derived2base
						 + "(" + derivedname + " *self) { return self; }\n");
				 
				 if (Utils.isPolymorphic(base)) {
					 m_output.write(
							 derivedname + "* __CDECL downcast_" + base2derived
							 + "(" + basename + " *self)");
					 m_output.write(" { return dynamic_cast<" + derivedname
							 + "*>(self); }\n");
					 m_output.write(
							 "RegData downcast_" + base2derived + "_proto[] = {\n" 
							 + "\t{\"arg0\", \"*" + basename + "\", 0, 0},\n"
							 + END_OF_LIST);
					 m_downCasters.add("\"dynamic_cast< " + derivedname 
							 + " >\", "
							 + "\"&" + derivedname + "\", "
							 + "downcast_" + base2derived + "_proto, "
							 + "(void*)&downcast_" + base2derived);
				 }
			}
		}
	}
	
	/**
	 * Writes a RegData entry which can be included in a scope.
	 * @param routine routine for which to create line
	 * @param skip indicates how many parameters to ignore
	 * @param with indicates whether this method is with an instance, or without an instance (static)
	 * @throws IOException if output operation fails
	 * @throws MissingInformationException if the program database is 
	 * incomplete.
	 */
	private void generateRegistrationLine(Routine routine, int nSkip, boolean with) 
		throws IOException, MissingInformationException
	{
		RoutineDeduction.ParameterTransformer retf =
			RoutineDeduction.deduceReturnTransformer(routine);
		
		int minArgs = Utils.minimalArgumentCount(routine),
			maxArgs = Utils.countParameters(routine);
		for (int nArguments = minArgs; nArguments <= maxArgs; 
				++nArguments) {
			if (routine.isConstructor()) {
				char symbol = routine.isExplicitConstructor() ? '%' : '*';
				m_output.write("{ \"" + symbol + "\" , \"constructor\", ");
			}
			else {
				// Write name
				Entity container = routine.getContainer();
				m_output.write("{\"");
				m_output.write((with && container instanceof Aggregate) 
								? routine.getName() : Utils.cleanFullName(routine));
				m_output.write("\", \"" 
						+ Formatters.formatSimpleType(retf.getRegDataType())
						+ "\", ");
			}
			// Write pointer to prototype
			String wrapperName = "routine_" + uid(routine) 
				+ (with ? "r" : "s") + nArguments;
			m_output.write(wrapperName + "_proto+" + nSkip + ", ");
			// Write pointer to implementation, unless this is a pure virtual method
			if (m_interceptorMethods.contains(routine)) {
				m_output.write("0},\n");
			}
			else {
				m_output.write("(void*)&" + wrapperName);
				m_output.write("},\n");
			}
		}
	}
	
	private void generateRegistrationLine(Field field, boolean with)
		throws IOException, MissingInformationException
	{
		RoutineDeduction.ParameterTransformer retf =
			RoutineDeduction.deduceReturnTransformer(field.getType(), ElementKind.VARIABLE);

		String fors = with ? "f" : "s";
		String identifier =
			(field.getContainer() instanceof Aggregate && with) ?
			field.getName() : Utils.cleanFullName(field);
		// - data_get
		m_output.write("{ \".data_" + identifier);
		m_output.write("\", \"" + Formatters.formatSimpleType(retf.getRegDataType()) + "\"");
		m_output.write(", 0, (void*)&data_get_" + uid(field) + fors);
		m_output.write(" },\n");
		// - data_set
		if (Filters.hasSetter(field)) {
			m_output.write("{ \".sink_" + identifier);
			m_output.write("\", \"void\", sink_" + uid(field) + fors + "_proto");
			m_output.write(", (void*)&data_set_" + uid(field) + fors);
			m_output.write(" },\n");
		}
	}
	
	/**
	 * Generates an array of pointers describing the routines contained in
	 * a scope.
	 * @param scope scope containing routines
	 * @throws IOException if output operation fails 
	 */
	public void generateRegistrationPrototype(Scope<Aggregate> scope, String classname,
		ConstCollection<InheritanceConnection> bases, boolean isAbstractClass,
		boolean mustHaveCtor, boolean hasAssign, boolean hasClone,
		boolean hasOutput, boolean hasDtor, boolean with, List<Routine> additional)
		throws IOException, MissingInformationException
	{
		m_output.write("RegData scope_" + scope.hashCode() + "[] = {\n");
		// Go through bases
		Iterator<InheritanceConnection> basesIterator = bases.iterator();
		if (basesIterator != null) {
			for (; basesIterator.hasNext(); ) {
				InheritanceConnection connection =
					basesIterator.next();
				if (connection.getVisibility() == Specifiers.Visibility.PUBLIC) {
					Aggregate base = connection.getBase();
					m_output.write("{\"");
					m_output.write(Utils.actualBaseName(connection));
					m_output.write("\", \"extends\", 0, (void*)&upcast_"
						+ uid(scope) + "_to_" + uid(base.getScope()));
					m_output.write("},\n");
				}
			}
		}
		// Go through routines in this scope
		boolean ctors = false;
		for (ContainedConnection<Aggregate, Routine> connection: scope.getRoutines()) {
			// Create an entry for each public routine
			Routine routine = (Routine)connection.getContained();
			if (routine.isConstructor() && isAbstractClass) continue;
			if (connection.getVisibility() == Specifiers.Visibility.PUBLIC
				&& !routine.isDestructor() 
				&& Filters.isAvailable(routine)) {
				generateRegistrationLine(routine, 0, with);
				ctors |= routine.isConstructor();
			}
		}
		// Go through data members in this scope
		for (ContainedConnection<Aggregate, Field> connection: scope.getFields()) {
			// Create an entry for public data members
			Field field = (Field)connection.getContained();
			if (Filters.isAvailable(field, connection)) 
				generateRegistrationLine(field, true);
		}
		// More special stuff
		if (!ctors && mustHaveCtor && !isAbstractClass) {
			m_output.write("{ \"*\", \"constructor\", 0, (void*)&ctor_"
				+ uid(scope));
			m_output.write("},\n");
		}
		if (hasAssign) {
			m_output.write("{ \"operator=\", \"void\", assign_" 
					+ uid(scope) + "_proto, (void*)&assign_" + uid(scope)
					+ "},\n");
		}
		if (hasClone) {
			m_output.write("{ \"clone\", \"*" + classname + "\", 0, "
					+ "(void*)&clone_" + uid(scope));
			m_output.write("},\n");
		}
		if (hasOutput) {
			m_output.write("{ \".print\", \"void\", 0, (void*)&output_"
				+ uid(scope));
			m_output.write("},\n");
			m_output.write("{ \".string\", \"@string\", 0, (void*)&toString_"
				+ uid(scope));
			m_output.write("},\n");
		}
		if (hasDtor) {
			m_output.write("{ \".\", \"destructor\", 0, (void*)&dtor_"
				+ uid(scope));
			m_output.write("},\n");
		}
		for (Routine routine: additional) {
			if (Filters.isAvailable(routine))
				generateRegistrationLine(routine, 1, false);
		}
		m_output.write(END_OF_LIST);
	}

	/**
	 * (internal) Extracts the declaration position of an entity.
	 * If the declaration cannot be found, a warning is printed
	 * to m_output (commented out, of course).
	 * This is used as an auxiliary function by generateIncludeDirectives.
	 * @throws IOException
	 */
	private SourceFile.DeclDefConnection safeGetDeclaration(Entity entity)
		throws IOException
	{
		SourceFile.DeclDefConnection decl = entity.getDeclaration();
		if (decl == null) {
			m_output.write("// " + entity.getFullName() + 
				": location specification may be missing?\n");
		}
		return decl;
	}
	
	/**
	 * Generates invocation of a conversion operator.
	 * @param op conversion operator routine
	 * @param self object being converted
	 * @return a C++ expression which can be used to invoke the conversion
	 * @throws MissingInformationException if the conversion operator is 
	 * somehow incomplete.
	 */
	/* package */ String conversionOperatorSyntax(Routine op, String self)
		throws MissingInformationException
	{
		return "CONVOP("+op.getReturnType().formatCpp()+"," + self + ")"; 
	}

	/**
	 * Removes alleged redundant 'const' notations that occur when the
	 * type is merely a primitive with no pointer/reference - thus constness
	 * makes no difference.
	 *  
	 * @param type given type
	 * @return a new modified type - or the original type if no changes
	 * were carried out
	 */
	/* package */ Type removeUnneededConstness(Type type) {
		Type.TypeNode root = type.getRootNode();
	
		if (root.getKind() == Type.TypeNode.NODE_LEAF) {
			try {
				Entity base = root.getBase();
				if (base instanceof Primitive 
					&& (root.getCV() | Specifiers.CVQualifiers.CONST) != 0) {
					type = new Type(new Type.TypeNode(base));
				}
			}
			catch (InappropriateKindException e) {
				// .. ignore
			}
		}
		return type;
	}
	
	/**
	 * @name Reporting
	 */
	//@{
	
	/**
	 * Reports the names of the classes which were successfully wrapped and
	 * registered.
	 */
	public void report(String[] classnames) {
		Set<String> requested = new HashSet<String>();
		for (int i = 0; i < classnames.length; ++i)
			if (!classnames[i].equals("*"))
				requested.add(classnames[i]);
		// Print header
		System.out.println("=================================");
		if (!m_subjects.isEmpty())
			System.out.println("| Registered classes:");
		// Print subjects
		for (Aggregate subject: m_subjects) {
			// - print name
			System.out.println("|   " + subject.getFullName());
			requested.removeAll(allPossibleNames(subject));
		}
		if (!m_globalFuncs.isEmpty()) 
			System.out.println("| Registered functions:");
		for (Entity func: m_globalFuncs) {
			System.out.println("|   " + func.getFullName());
		}
		// Print header for global variables
		if (!m_globalDataMembers.isEmpty())
			System.out.println("| Registered variables:");
		for (Entity var: m_globalDataMembers) {
			System.out.println("|   " + var.getFullName());
		}
		// Functions
		unmiss(requested, m_globalFuncs);
		unmiss(requested, m_namespaces);
		unmiss(requested, m_enums);
		unmiss(requested, m_typedefs);
		// Print those classes that were not found
		if (!requested.isEmpty())
			System.out.println("| Missed classes:");
		for (String missed: requested) {
			System.out.println("|   " + missed);
		}
		// Print footer
		System.out.println("=================================");
		if (!requested.isEmpty())
			System.err.println("griffin: WARNING - Some components could not be found.");
	}
	
	private static void unmiss(Collection<String> requested, Collection<? extends Entity> found)
	{
		for (Entity entity: found) {
			requested.removeAll(allPossibleNames(entity));
		}
	}

    private String generateNamespaceName() {
        final int rnd = (new Random()).nextInt();

        // We're randomizing a namespace name instead of anonymous namespace,
        // because of a weird bug, where g++ ignores the anonymous namespace in
        // our case.
        return ("Robin_" + Integer.toString(rnd,16)).replace("-","_");
    }

	// Private members
	private Map<Object, Integer> m_uidMap;
	private int m_uidNext;
	private List<Field> m_globalDataMembers;
	private List<String> m_downCasters;
	private List<RegData> m_entry;

	private List<Aggregate> m_interceptors;
	private Set<Routine> m_interceptorMethods;

    // random namespace name
    private String m_randomNamespace;
    
    // some code snippets are only allowed to appear once
    private Set<String> m_included_snippets;
	
	// Code skeletons
	private static final String END_OF_LIST = "\t{ 0,0,0,0 }\n};\n\n";

	
}
