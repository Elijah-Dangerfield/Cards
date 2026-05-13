package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun <T> NonLazyVerticalGrid(
    modifier: Modifier = Modifier,
    columns: Int,
    data: List<T>,
    verticalSpacing: Dp = 0.dp,
    horizontalSpacing: Dp = 0.dp,
    itemContent: @Composable (index: Int, item: T) -> Unit
) {

    Box(modifier = modifier) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {

            val numOfRows = (data.size / columns) + (if (data.size % columns > 0) 1 else 0)

            repeat(numOfRows) { i ->

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                    ,
                    horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)
                ) {

                    repeat(columns) { j ->

                        val index = j + (i * columns)

                        if (index < data.size) {
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                itemContent(index, data[index])
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(verticalSpacing))
            }
        }
    }
}