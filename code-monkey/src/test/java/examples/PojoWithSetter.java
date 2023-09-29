package examples;

public class PojoWithSetter {

  private final String firstName;
  private String lastName;

  public PojoWithSetter(String a) {
    this.firstName = a;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }
}
