package examples;

public class FailingPojoWithTwoDistinctConstructors {

  private String first;
  private int second;

  public FailingPojoWithTwoDistinctConstructors(String first) {
    this.first = first;
  }

  public FailingPojoWithTwoDistinctConstructors(int second) {
    this.second = second;
  }
}
