package se.kth.castor.rockstofetch.extract;

public enum ClassSerializationType {
  /**
   * Instances of this class will be mocked.
   */
  MOCK(true), // FIXME: We replay this, so fine...
  /**
   * Instances of this class will be constructed using java statements.
   */
  CONSTRUCT(true),
  /**
   * Instances of this class are allowed as receiver but a {@literal FIXME} is generated in the code
   * instead.
   */
  FIXME(true),
  /**
   * Methods are ignored if they need an instance of this class.
   */
  ABORT(false);

  private final boolean isAllowedAsReceiver;

  ClassSerializationType(boolean isAllowedAsReceiver) {
    this.isAllowedAsReceiver = isAllowedAsReceiver;
  }

  public boolean isAllowedAsReceiver() {
    return isAllowedAsReceiver;
  }
}
