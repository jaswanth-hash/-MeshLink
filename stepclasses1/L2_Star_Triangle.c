#include <stdio.h>

int main(void) {
    int rows, row, star;

    scanf("%d", &rows);
    for (row = 1; row <= rows; row++) {
        for (star = 1; star <= row; star++)
            printf("* ");
        printf("\n");
    }
    return 0;
}