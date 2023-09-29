package examples;

public class PojoWithFactoryMethod {

  private final String firstName;
  private final String lastName;

  private PojoWithFactoryMethod(String firstName, String lastName) {
    this.firstName = firstName;
    this.lastName = lastName;
  }

  public static PojoWithFactoryMethod create(String first, String last) {
    return new PojoWithFactoryMethod(first, last);
  }
}
