package examples;

public class PojoWithFactoryMethodAndField {

  private final String firstName;
  public String lastName;

  private PojoWithFactoryMethodAndField(String firstName) {
    this.firstName = firstName;
  }

  public static PojoWithFactoryMethodAndField create(String first) {
    return new PojoWithFactoryMethodAndField(first);
  }
}
