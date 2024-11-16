package org.example;

import java.util.Set;


public record TestClass(
    Set<Set<String>> test
)
{}

/*
  {
      "test": []
  } 11
 */