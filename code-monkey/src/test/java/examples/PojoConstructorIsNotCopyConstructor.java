package examples;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class PojoConstructorIsNotCopyConstructor {

  private final List<String> list;

  public PojoConstructorIsNotCopyConstructor(int size) {
    this.list = new ArrayList<>(size);
  }

}
