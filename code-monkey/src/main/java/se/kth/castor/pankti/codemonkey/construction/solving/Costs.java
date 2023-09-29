package se.kth.castor.pankti.codemonkey.construction.solving;

public final class Costs {

  /**
   * Using the correct constructor is the best case
   */
  public static final int CALL_CONSTRUCTOR = 1;
  /**
   * Calling a factory method is typically a good idea, but we prefer a public constructor
   */
  public static final int CALL_FACTORY_METHOD = 2;
  /**
   * If needed we can fall back to the default constructor and use setters/field assignments
   */
  public static final int CALL_DEFAULT_CONSTRUCTOR = 3;
  /**
   * Setters are not great but better than fields
   */
  public static final int CALL_SETTER = 2;
  /**
   * Field assignments are the most brittle and have the least priority
   */
  public static final int ASSIGN_FIELD = CALL_SETTER + 1;
  /**
   * It's the only way and it's free
   */
  public static final int USE_ENUM_CONSTANT = 0;
  /**
   * It's probably nicer to re-use a static field instead of a constructor.
   */
  public static final int USE_STATIC_FIELD = 0;
  /**
   * Mocking is the last resort. Always.
   */
  public static final int MOCK_OBJECT = 100_000;
  /**
   * Mutually exclusive with mocking, either of them is fine. But do not use them unless you need
   * to.
   */
  public static final int FIXME_CONSTRUCT_OBJECT = MOCK_OBJECT;
  /**
   * Prefer references over mocks
   */
  public static final int USE_OBJECT_REFERENCE = MOCK_OBJECT - 1;

  private Costs() {
    throw new UnsupportedOperationException("No instantiation");
  }
}
