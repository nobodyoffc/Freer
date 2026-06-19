package com.fc.freer.utils;

public enum ChooseMode {
    CHOOSE_ONE_RETURN,  // For the case of isSingleChoice null (no checkbox, click returns)
    CHOOSE_ONE,         // For the case of isSingleChoice true (radio button mode)
    CHOOSE_MULTI, // For the case of isSingleChoice false (checkbox mode)
    WITHOUT_CHOOSE,
    WITHOUT_CHOOSE_WITH_DELETE
}