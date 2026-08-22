#include <stdio.h>

int main(void) {
    int number, divisor, prime = 1;

    scanf("%d", &number);
    if (number < 2)
        prime = 0;
    for (divisor = 2; divisor * divisor <= number; divisor++)
        if (number % divisor == 0)
            prime = 0;

    if (prime)
        printf("Prime\n");
    else
        printf("Not Prime\n");
    return 0;
}