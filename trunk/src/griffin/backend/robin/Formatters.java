package backend.robin;

import backend.Utils;
import sourceanalysis.Aggregate;
import sourceanalysis.*;
import sourceanalysis.SourceFile.DeclDefConnection;

public class Formatters {

	static String formatParameter(Type type, String name) 
	{
		// Handle extra referencing
		if (Filters.needsExtraReferencing(type)) {
			name = "*" + name;  // @@@
		}
		// Handle redundant referencing
		if (type.isReference()
				&& Filters.isSmallPrimitive(type.getBaseType())) {
			type = dereference(type);
		}
		return type.formatCpp(name);
	}
	
	static String formatArgument(Type type, String name) 
	{
		if (Filters.needsExtraReferencing(type))
			return "touchdown(" + name + ")";
		else
			return name;
	}
	
	static String formatLocation(Entity entity)
	{
		DeclDefConnection declaration = entity.getDeclaration();
		if (declaration == null) {
			return "location unknown";
		}
		else {
			return "from " + declaration.getSourceFilename() 
			       + ":" + declaration.where().getStart().line;
		}
	}
	
	static String formatMember(Field field)
	{
		if (field.getContainer() instanceof Aggregate)
			return "self->" + field.getName();
		else
			return Utils.cleanFullName(field);
	}
	
	static String formatExpression(Type type, String expression)
	{
		// Handle redundant referencing
		if (type.isReference()
				&& Filters.isPrimitive(type.getBaseType())) {
			type = dereference(type);
		}
		Type touchupType = Filters.getTouchup(type);
		if (touchupType == null)
			return expression;
		else
			return "touchup(" + expression + ")";
	}
	
	static String formatDeclaration(Type type, String name, char extraRefScheme)
	{
		// Handle redundant referencing
		if (type.isReference()
				&& Filters.isPrimitive(type.getBaseType())) {
			type = dereference(type);
		}
		// Handle touch-up
		Type touchupType = Filters.getTouchup(type);
		if (touchupType != null) type = touchupType;
		// Handle extra referencing
		if (Filters.needsExtraReferencing(type)) {
			name = extraRefScheme + name;
		}
		return type.formatCpp(name);
	}

	/**
	 * Removes any reference notations from a type expression.
	 * e.g. "const int&" converts to "const int". 
	 * @param type original type expression
	 * @return a new type expression without a reference
	 */
	static /* package */ Type dereference(Type type)
	{
		Type.TypeNode root = type.getRootNode();
		// Descend until node is not a reference
		while (root.getKind() == Type.TypeNode.NODE_REFERENCE) {
			root = (Type.TypeNode)root.getFirstChild();
		}
		return new Type(root);
	}
	
}