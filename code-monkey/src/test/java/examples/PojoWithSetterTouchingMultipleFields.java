package examples;

public class PojoWithSetterTouchingMultipleFields {

  private int x;
  private int y;
  private int z;

  public PojoWithSetterTouchingMultipleFields() {
  }

  public void setValues(int x, int y, int z) {
    this.x = x;
    this.y = y;
    this.z = z;
  }

}
