package sourceanalysis;

/**
 * Implies that some required information is missing.
 */
public class MissingInformationException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 7499219543051244272L;

	/**
	 * Constructor for MissingInformationException.
	 */
	public MissingInformationException() {
		super();
	}

	/**
	 * Constructor for MissingInformationException.
	 * @param s error string
	 */
	public MissingInformationException(String s) {
		super(s);
	}

}
