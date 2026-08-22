#include <stdio.h>

int main(void) {
    int number, original, reversed = 0, digit;

    scanf("%d", &number);
    original = number;
    while (number != 0) {
        digit = number % 10;
        reversed = reversed * 10 + digit;
        number /= 10;
    }

    if (original == reversed)
        printf("Palindrome\n");
    else
        printf("Not Palindrome\n");
    return 0;
}