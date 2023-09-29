package examples;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class PojoCopiesInputCollection {

  private final List<String> list;
  private final Collection<String> collection;
  private final Map<String, Integer> map;

  public PojoCopiesInputCollection(
      List<String> list,
      Collection<String> collection,
      Map<String, Integer> map
  ) {
    this.list = new ArrayList<>(list);
    this.collection = new HashSet<>(collection);
    this.map = new HashMap<>(map);
  }

}
